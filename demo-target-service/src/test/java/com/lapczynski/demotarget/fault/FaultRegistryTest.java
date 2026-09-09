package com.lapczynski.demotarget.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Safety properties of the fault subsystem.
 *
 * <p>These are the most important tests in this module. A fault-injection facility that can be
 * talked into an unbounded or permanent fault is not a demo tool, it is a way to break the machine
 * it runs on — so each guarantee is asserted directly rather than inferred from the code.
 *
 * <p>Time is driven by a mutable {@link Clock}, so expiry is proven in milliseconds instead of by
 * waiting out a thirty-minute ceiling. A test that takes half an hour is a test nobody runs.
 */
class FaultRegistryTest {

  private static final Instant T0 = Instant.parse("2026-09-09T12:00:00Z");

  /** A clock the test can move forward at will. */
  private static final class MovableClock extends Clock {
    private Instant now = T0;

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    void advance(Duration amount) {
      now = now.plus(amount);
    }
  }

  private final MovableClock clock = new MovableClock();

  private FaultRegistry enabledRegistry() {
    return new FaultRegistry(clock, true);
  }

  @Nested
  @DisplayName("disabled by default")
  class DisabledByDefault {

    @Test
    @DisplayName("injection is refused when not explicitly enabled")
    void injectionRefusedWhenDisabled() {
      FaultRegistry registry = new FaultRegistry(clock, false);

      assertThatThrownBy(
              () ->
                  registry.inject(
                      FaultType.LATENCY, Map.of("millis", "500"), Duration.ofMinutes(1), "test"))
          .as("an accidentally deployed image must be inert")
          .isInstanceOf(FaultRegistry.FaultInjectionDisabledException.class);

      assertThat(registry.activeFaults()).isEmpty();
    }

    @Test
    @DisplayName("clearing everything works even when injection is disabled")
    void clearAllAlwaysAvailable() {
      FaultRegistry registry = new FaultRegistry(clock, false);

      assertThat(registry.clearAll())
          .as("the way out must never be gated on configuration")
          .isZero();
    }
  }

  @Nested
  @DisplayName("every fault expires")
  class Expiry {

    @ParameterizedTest
    @EnumSource(FaultType.class)
    @DisplayName("a fault stops applying once its deadline passes")
    void faultExpires(FaultType type) {
      FaultRegistry registry = enabledRegistry();
      registry.inject(type, Map.of(), Duration.ofMinutes(1), "test");

      assertThat(registry.isActive(type)).isTrue();

      clock.advance(Duration.ofMinutes(1).plusSeconds(1));

      assertThat(registry.isActive(type)).as("%s outlived its deadline", type).isFalse();
    }

    @ParameterizedTest
    @EnumSource(FaultType.class)
    @DisplayName("expiry applies without the sweeper ever running")
    void expiryDoesNotDependOnSweeper(FaultType type) {
      FaultRegistry registry = enabledRegistry();
      registry.inject(type, Map.of(), Duration.ofMinutes(2), "test");

      clock.advance(Duration.ofMinutes(3));

      // sweepExpired() is deliberately never called here.
      assertThat(registry.active(type))
          .as("a wedged or misconfigured scheduler must not be able to extend a fault")
          .isEmpty();
      assertThat(registry.activeFaults()).doesNotContainKey(type);
    }

    @ParameterizedTest
    @EnumSource(FaultType.class)
    @DisplayName("a duration beyond the type ceiling is clamped, not honoured")
    void durationIsClampedToCeiling(FaultType type) {
      FaultRegistry registry = enabledRegistry();

      ActiveFault fault = registry.inject(type, Map.of(), Duration.ofDays(7), "test");

      assertThat(Duration.between(fault.activatedAt(), fault.expiresAt()))
          .as("%s must not be able to stay active for a week", type)
          .isLessThanOrEqualTo(type.maxDuration());
    }

    @Test
    @DisplayName("a fault cannot be constructed without an expiry")
    void expiryIsMandatory() {
      assertThatThrownBy(() -> new ActiveFault(FaultType.LATENCY, Map.of(), T0, T0, "test"))
          .as("there is no 'until I turn it off' mode, by construction")
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a non-positive duration is rejected")
    void nonPositiveDurationRejected() {
      FaultRegistry registry = enabledRegistry();

      assertThatThrownBy(() -> registry.inject(FaultType.LATENCY, Map.of(), Duration.ZERO, "test"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("magnitudes are bounded")
  class Magnitudes {

    @Test
    @DisplayName("latency is capped at the compiled-in maximum")
    void latencyIsCapped() {
      ActiveFault fault =
          enabledRegistry()
              .inject(FaultType.LATENCY, Map.of("millis", "600000"), Duration.ofMinutes(1), "test");

      assertThat(fault.intParam("millis", 0))
          .isEqualTo((int) FaultType.MAX_LATENCY.toMillis())
          .isLessThanOrEqualTo(5_000);
    }

    @Test
    @DisplayName("error rate is confined to [0, 1]")
    void errorRateIsBounded() {
      FaultRegistry registry = enabledRegistry();

      assertThat(
              registry
                  .inject(FaultType.ERROR_RATE, Map.of("rate", "12.5"), Duration.ofMinutes(1), "t")
                  .doubleParam("rate", -1))
          .isEqualTo(1.0);

      assertThat(
              registry
                  .inject(FaultType.ERROR_RATE, Map.of("rate", "-3"), Duration.ofMinutes(1), "t")
                  .doubleParam("rate", -1))
          .isZero();
    }

    @Test
    @DisplayName("CPU threads never exceed the host cap, which always leaves a core free")
    void cpuThreadsAreCapped() {
      ActiveFault fault =
          enabledRegistry()
              .inject(FaultType.CPU_PRESSURE, Map.of("threads", "512"), Duration.ofMinutes(1), "t");

      int cap = FaultType.maxCpuThreads();
      assertThat(fault.intParam("threads", 0)).isEqualTo(cap);
      assertThat(cap)
          .as("at least one core must stay free to serve the request that ends the fault")
          .isLessThan(Runtime.getRuntime().availableProcessors() + 1)
          .isLessThanOrEqualTo(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    @Test
    @DisplayName("a garbage magnitude falls back to a safe default rather than failing open")
    void unparseableValuesFallBackSafely() {
      ActiveFault fault =
          enabledRegistry()
              .inject(
                  FaultType.LATENCY,
                  Map.of("millis", "as long as possible"),
                  Duration.ofMinutes(1),
                  "test");

      assertThat(fault.intParam("millis", -1))
          .as("an unparseable value must not become an unbounded one")
          .isBetween(0, (int) FaultType.MAX_LATENCY.toMillis());
    }

    @Test
    @DisplayName("every fault type declares a finite ceiling")
    void everyTypeHasAFiniteCeiling() {
      SoftAssertions softly = new SoftAssertions();
      for (FaultType type : FaultType.values()) {
        softly
            .assertThat(type.maxDuration())
            .as("%s ceiling", type)
            .isGreaterThan(Duration.ZERO)
            .isLessThanOrEqualTo(Duration.ofMinutes(30));
      }
      softly.assertAll();
    }
  }

  @Nested
  @DisplayName("control")
  class Control {

    @Test
    @DisplayName("faults can be cleared individually and in bulk")
    void faultsCanBeCleared() {
      FaultRegistry registry = enabledRegistry();
      registry.inject(FaultType.LATENCY, Map.of(), Duration.ofMinutes(1), "test");
      registry.inject(FaultType.ERROR_RATE, Map.of(), Duration.ofMinutes(1), "test");

      assertThat(registry.clear(FaultType.LATENCY)).isTrue();
      assertThat(registry.isActive(FaultType.LATENCY)).isFalse();
      assertThat(registry.isActive(FaultType.ERROR_RATE)).isTrue();

      assertThat(registry.clearAll()).isEqualTo(1);
      assertThat(registry.activeFaults()).isEmpty();
    }

    @Test
    @DisplayName("clearing a fault that is not active reports so rather than failing")
    void clearingInactiveFaultIsSafe() {
      assertThat(enabledRegistry().clear(FaultType.CPU_PRESSURE)).isFalse();
    }

    @Test
    @DisplayName("re-injecting a type replaces it rather than stacking")
    void reinjectionReplaces() {
      FaultRegistry registry = enabledRegistry();
      registry.inject(FaultType.LATENCY, Map.of("millis", "100"), Duration.ofMinutes(1), "test");
      registry.inject(FaultType.LATENCY, Map.of("millis", "900"), Duration.ofMinutes(1), "test");

      assertThat(registry.activeFaults()).hasSize(1);
      assertThat(registry.active(FaultType.LATENCY).orElseThrow().intParam("millis", 0))
          .isEqualTo(900);
    }

    @Test
    @DisplayName("the active view reports what is running, for the operator to see")
    void activeViewIsVisible() {
      FaultRegistry registry = enabledRegistry();
      registry.inject(FaultType.LATENCY, Map.of("millis", "250"), Duration.ofMinutes(5), "alice");

      ActiveFault fault = registry.activeFaults().get(FaultType.LATENCY);

      assertThat(fault.activatedBy()).isEqualTo("alice");
      assertThat(fault.parameters()).containsEntry("millis", "250");
      assertThat(fault.remaining(clock.instant())).isEqualTo(Duration.ofMinutes(5));
    }
  }
}
