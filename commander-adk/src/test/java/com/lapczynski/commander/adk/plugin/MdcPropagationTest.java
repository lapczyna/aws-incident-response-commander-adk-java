package com.lapczynski.commander.adk.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("Correlation context across scheduler boundaries")
class MdcPropagationTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  @DisplayName("context captured at assembly is visible on the subscribing thread")
  void contextCrossesThreads() {
    // The bug this exists to prevent: MDC is thread-local, ADK schedules work elsewhere, and the
    // incident id is silently absent on exactly the operations worth correlating.
    MDC.put("incidentId", "abc-123");

    ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();

    // Observed downstream of withContext, which is the direction it applies in and the direction
    // real consumers read events from a runner.
    List<Integer> result =
        MdcPropagation.withContext(Flowable.range(1, 3).subscribeOn(Schedulers.io()))
            .doOnNext(value -> seen.add(String.valueOf(MDC.get("incidentId"))))
            .toList()
            .blockingGet();

    assertThat(result).containsExactly(1, 2, 3);
    assertThat(seen).containsExactly("abc-123", "abc-123", "abc-123");
  }

  @Test
  @DisplayName("the previous context is restored, not cleared")
  void previousContextIsRestored() {
    // On a pooled thread the "previous" context belongs to whatever ran before. Clearing it would
    // trade leakage in one direction for leakage in the other.
    MDC.put("incidentId", "outer");

    MdcPropagation.run(Map.of("incidentId", "inner"), () -> assertInner());

    assertThat(MDC.get("incidentId")).isEqualTo("outer");
  }

  @Test
  @DisplayName("an empty captured context clears rather than inheriting the pooled thread's")
  void emptyContextDoesNotInherit() {
    // A stale value attaching itself to an unrelated run is worse than a blank field: a blank one
    // is noticed, and a wrong one is trusted.
    MDC.put("incidentId", "stale-from-a-previous-run");

    MdcPropagation.run(Map.of(), () -> assertThat(MDC.get("incidentId")).isNull());

    assertThat(MDC.get("incidentId")).isEqualTo("stale-from-a-previous-run");
  }

  @Test
  @DisplayName("context is restored even when the work throws")
  void restoredOnFailure() {
    MDC.put("incidentId", "outer");

    try {
      MdcPropagation.run(
          Map.of("incidentId", "inner"),
          () -> {
            throw new IllegalStateException("boom");
          });
    } catch (IllegalStateException expected) {
      // The assertion is what happens after.
    }

    assertThat(MDC.get("incidentId")).isEqualTo("outer");
  }

  @Test
  @DisplayName("two concurrent streams do not see each other's context")
  void concurrentStreamsAreIsolated() {
    // Four specialists run in parallel on a shared pool, so this is the normal case. If contexts
    // bled between them, every log line from a parallel investigation would be suspect.
    MDC.put("incidentId", "first");
    Flowable<String> first =
        MdcPropagation.withContext(Flowable.range(1, 20).subscribeOn(Schedulers.io()))
            .map(value -> String.valueOf(MDC.get("incidentId")));

    MDC.put("incidentId", "second");
    Flowable<String> second =
        MdcPropagation.withContext(Flowable.range(1, 20).subscribeOn(Schedulers.io()))
            .map(value -> String.valueOf(MDC.get("incidentId")));

    List<String> firstSeen = first.toList().blockingGet();
    List<String> secondSeen = second.toList().blockingGet();

    assertThat(firstSeen).containsOnly("first");
    assertThat(secondSeen).containsOnly("second");
  }

  private static void assertInner() {
    assertThat(MDC.get("incidentId")).isEqualTo("inner");
  }
}
