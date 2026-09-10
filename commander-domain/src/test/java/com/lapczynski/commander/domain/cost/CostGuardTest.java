package com.lapczynski.commander.domain.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The model cost guard")
class CostGuardTest {

  private static final Instant SEPTEMBER = Instant.parse("2026-09-09T12:00:00Z");
  private static final Instant LATER_IN_SEPTEMBER = Instant.parse("2026-09-28T09:00:00Z");
  private static final Instant OCTOBER = Instant.parse("2026-10-01T00:00:00Z");

  private static final BigDecimal LIMIT = new BigDecimal("10.00");

  @Nested
  @DisplayName("refusing")
  class Refusing {

    @Test
    @DisplayName("a call within budget is allowed")
    void withinBudget() {
      CostGuard guard = new CostGuard(LIMIT, SEPTEMBER);

      assertThat(guard.check(new BigDecimal("0.05"), SEPTEMBER))
          .isInstanceOf(CostGuard.Verdict.Allowed.class);
    }

    @Test
    @DisplayName("a call that would exceed the budget is refused")
    void overBudget() {
      CostGuard guard = new CostGuard(LIMIT, SEPTEMBER);
      guard.record(new BigDecimal("9.99"), SEPTEMBER);

      assertThat(guard.check(new BigDecimal("0.02"), SEPTEMBER))
          .isInstanceOf(CostGuard.Verdict.Refused.class);
    }

    @Test
    @DisplayName("a call landing exactly on the limit is allowed")
    void exactlyOnTheLimit() {
      // The limit is what may be spent, not what may not be reached. Refusing here would make the
      // effective budget depend on rounding.
      CostGuard guard = new CostGuard(LIMIT, SEPTEMBER);
      guard.record(new BigDecimal("9.50"), SEPTEMBER);

      assertThat(guard.check(new BigDecimal("0.50"), SEPTEMBER))
          .isInstanceOf(CostGuard.Verdict.Allowed.class);
    }

    @Test
    @DisplayName("the guard fails closed once the budget is gone")
    void failsClosed() {
      // The point of the whole class. A guard that warned and continued would be a description of
      // what already happened rather than a limit.
      CostGuard guard = new CostGuard(LIMIT, SEPTEMBER);
      guard.record(LIMIT, SEPTEMBER);

      for (String amount : List.of("0.000001", "0.01", "100.00")) {
        assertThat(guard.check(new BigDecimal(amount), SEPTEMBER))
            .describedAs("a call costing %s after the budget is spent", amount)
            .isInstanceOf(CostGuard.Verdict.Refused.class);
      }
    }

    @Test
    @DisplayName("a refusal says what was spent and by how much the call would overshoot")
    void refusalExplainsItself() {
      CostGuard guard = new CostGuard(LIMIT, SEPTEMBER);
      guard.record(new BigDecimal("9.80"), SEPTEMBER);

      CostGuard.Verdict.Refused refused =
          (CostGuard.Verdict.Refused) guard.check(new BigDecimal("0.50"), SEPTEMBER);

      assertThat(refused.spentThisMonth()).isEqualByComparingTo("9.80");
      assertThat(refused.overBy()).isEqualByComparingTo("0.30");
      assertThat(refused.explanation()).contains("exhausted");
    }

    @Test
    @DisplayName("a zero limit refuses everything that costs anything")
    void zeroLimit() {
      // The configuration for "this profile must not spend money". A free model costs zero and is
      // unaffected; anything priced is refused immediately.
      CostGuard guard = new CostGuard(BigDecimal.ZERO, SEPTEMBER);

      assertThat(guard.check(BigDecimal.ZERO, SEPTEMBER))
          .isInstanceOf(CostGuard.Verdict.Allowed.class);
      assertThat(guard.check(new BigDecimal("0.000001"), SEPTEMBER))
          .isInstanceOf(CostGuard.Verdict.Refused.class);
    }
  }

  @Nested
  @DisplayName("the month boundary")
  class MonthBoundary {

    @Test
    @DisplayName("spend accumulates within a calendar month")
    void accumulates() {
      CostGuard guard = new CostGuard(LIMIT, SEPTEMBER);
      guard.record(new BigDecimal("1.00"), SEPTEMBER);
      guard.record(new BigDecimal("2.50"), LATER_IN_SEPTEMBER);

      assertThat(guard.spentThisMonth(LATER_IN_SEPTEMBER)).isEqualByComparingTo("3.50");
      assertThat(guard.callsThisMonth(LATER_IN_SEPTEMBER)).isEqualTo(2);
    }

    @Test
    @DisplayName("the total resets when the month rolls over")
    void resetsOnRollover() {
      CostGuard guard = new CostGuard(LIMIT, SEPTEMBER);
      guard.record(LIMIT, SEPTEMBER);

      assertThat(guard.check(new BigDecimal("1.00"), SEPTEMBER))
          .isInstanceOf(CostGuard.Verdict.Refused.class);
      assertThat(guard.check(new BigDecimal("1.00"), OCTOBER))
          .isInstanceOf(CostGuard.Verdict.Allowed.class);
      assertThat(guard.spentThisMonth(OCTOBER)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a call recorded after the rollover starts the new month rather than vanishing")
    void rolloverBetweenCheckAndRecord() {
      // The month can turn over between checking and recording. Discarding the call would lose
      // real spend; attributing it to the old month would understate the new one.
      CostGuard guard = new CostGuard(LIMIT, SEPTEMBER);
      guard.record(new BigDecimal("4.00"), SEPTEMBER);
      guard.record(new BigDecimal("0.25"), OCTOBER);

      assertThat(guard.spentThisMonth(OCTOBER)).isEqualByComparingTo("0.25");
      assertThat(guard.callsThisMonth(OCTOBER)).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("arithmetic")
  class Arithmetic {

    @Test
    @DisplayName("many small calls sum exactly, with no floating-point drift")
    void noDrift() {
      // The reason this class uses BigDecimal. Ten thousand calls at 0.0001 is exactly 1.00; in
      // binary floating point it is not, and the error is in the direction that lets spending
      // creep past a limit unnoticed.
      CostGuard guard = new CostGuard(LIMIT, SEPTEMBER);
      BigDecimal each = new BigDecimal("0.0001");

      for (int i = 0; i < 10_000; i++) {
        guard.record(each, SEPTEMBER);
      }

      assertThat(guard.spentThisMonth(SEPTEMBER)).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("a negative cost is refused rather than credited")
    void negativeCostRejected() {
      // A negative amount would be a way to spend past the limit by "refunding" first.
      CostGuard guard = new CostGuard(LIMIT, SEPTEMBER);

      assertThatThrownBy(() -> guard.record(new BigDecimal("-1.00"), SEPTEMBER))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a negative limit is refused at construction")
    void negativeLimitRejected() {
      assertThatThrownBy(() -> new CostGuard(new BigDecimal("-1"), SEPTEMBER))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("concurrency")
  class Concurrency {

    @Test
    @DisplayName("concurrent recording loses nothing")
    void concurrentRecording() throws InterruptedException {
      // Four specialists run in parallel and every one of them makes model calls, so this is the
      // normal case rather than an edge case. A lost update here is spend that the limit never
      // sees.
      CostGuard guard = new CostGuard(new BigDecimal("1000"), SEPTEMBER);
      int threads = 8;
      int perThread = 500;

      CountDownLatch start = new CountDownLatch(1);
      try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
        for (int t = 0; t < threads; t++) {
          pool.submit(
              () -> {
                try {
                  start.await();
                  for (int i = 0; i < perThread; i++) {
                    guard.record(new BigDecimal("0.001"), SEPTEMBER);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      }

      assertThat(guard.callsThisMonth(SEPTEMBER)).isEqualTo((long) threads * perThread);
      assertThat(guard.spentThisMonth(SEPTEMBER)).isEqualByComparingTo("4.000");
    }
  }

  @Nested
  @DisplayName("pricing")
  class Pricing {

    @Test
    @DisplayName("Nova Lite pricing produces the figure the documentation quotes")
    void novaLiteSampleWorkload() {
      // docs/cost.md states USD 0.54/month for 50 incidents at ~120k input and ~15k output
      // tokens each. This keeps that claim honest: if the arithmetic or the quoted prices change,
      // the number in the documentation is wrong and this fails. It was written when the
      // documentation said 0.42 and the arithmetic said otherwise.
      ModelPricing novaLite =
          new ModelPricing("amazon.nova-lite-v1:0", new BigDecimal("0.06"), new BigDecimal("0.24"));

      BigDecimal perIncident = novaLite.costOf(120_000, 15_000);
      BigDecimal monthly = perIncident.multiply(BigDecimal.valueOf(50));

      assertThat(monthly).isEqualByComparingTo("0.54");
      assertThat(monthly).isLessThan(new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("a free model costs nothing however many tokens it uses")
    void freeModel() {
      ModelPricing local = ModelPricing.free("llama3.1:8b");

      assertThat(local.isFree()).isTrue();
      assertThat(local.costOf(10_000_000, 10_000_000)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("negative token counts are refused")
    void negativeTokens() {
      ModelPricing pricing = new ModelPricing("m", new BigDecimal("1.00"), new BigDecimal("1.00"));

      assertThatThrownBy(() -> pricing.costOf(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
