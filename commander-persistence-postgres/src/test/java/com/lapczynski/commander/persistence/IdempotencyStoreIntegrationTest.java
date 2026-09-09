package com.lapczynski.commander.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lapczynski.commander.application.port.IdempotencyStore;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Exactly-once execution is a definition-of-done item: a duplicate approval must never execute a
 * remediation twice. The decisive test here is the concurrent one, because the single-threaded case
 * would also pass with a naive read-then-write implementation that races.
 */
class IdempotencyStoreIntegrationTest extends PostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");

  @Autowired private IdempotencyStore store;
  @Autowired private IncidentRepository incidents;
  @Autowired private JdbcClient jdbc;

  private IncidentId incidentId;
  private ActionFingerprint fingerprint;

  @BeforeEach
  void setUp() {
    truncateIncidents(jdbc);

    Incident incident =
        Incident.open(
            IncidentId.newId(),
            "Latency after deploy",
            new ServiceRef("checkout", "demo"),
            Severity.SEV2,
            T0);
    incidents.create(incident, Actor.SYSTEM);
    incidentId = incident.id();

    ProposedAction action =
        new ProposedAction(
            ActionType.ROLLBACK_DEPLOYMENT,
            new ResourceRef(
                "arn:aws:ecs:eu-west-1:123456789012:service/demo/checkout",
                "123456789012",
                "eu-west-1",
                "demo",
                "ecs:service"),
            Map.of("taskDefinition", "41"),
            "Roll back to task definition 41");
    fingerprint = ActionFingerprint.of(action, incidentId, 4L);
  }

  @Test
  @DisplayName("the first claim succeeds")
  void firstClaimWins() {
    assertThat(store.claim(fingerprint, incidentId)).isTrue();
  }

  @Test
  @DisplayName("a second claim on the same fingerprint is refused")
  void secondClaimIsRefused() {
    store.claim(fingerprint, incidentId);

    assertThat(store.claim(fingerprint, incidentId))
        .as("this is what stops a replayed approval from executing the rollback twice")
        .isFalse();
  }

  @Test
  @DisplayName("a completed execution exposes its stored result to later callers")
  void completedExecutionReturnsStoredResult() {
    store.claim(fingerprint, incidentId);
    store.complete(fingerprint, IdempotencyStore.Outcome.SUCCEEDED, "{\"revision\":41}");

    IdempotencyStore.Record record = store.find(fingerprint).orElseThrow();

    assertThat(record.outcome()).isEqualTo(IdempotencyStore.Outcome.SUCCEEDED);
    assertThat(record.result()).contains("41");
    assertThat(record.fingerprint()).isEqualTo(fingerprint);
  }

  @Test
  @DisplayName("completing an unclaimed fingerprint is refused")
  void completingWithoutClaimIsRefused() {
    assertThatThrownBy(() -> store.complete(fingerprint, IdempotencyStore.Outcome.SUCCEEDED, "{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("never claimed");
  }

  @Test
  @DisplayName("completing twice is refused, so a result cannot be silently rewritten")
  void doubleCompletionIsRefused() {
    store.claim(fingerprint, incidentId);
    store.complete(fingerprint, IdempotencyStore.Outcome.SUCCEEDED, "{\"first\":true}");

    assertThatThrownBy(
            () -> store.complete(fingerprint, IdempotencyStore.Outcome.FAILED, "{\"second\":true}"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(store.find(fingerprint).orElseThrow().result()).contains("first");
  }

  @Test
  @DisplayName("a different fingerprint is a different action and claims independently")
  void differentFingerprintsAreIndependent() {
    ProposedAction other =
        new ProposedAction(
            ActionType.RESTART_ECS_TASK,
            new ResourceRef(
                "arn:aws:ecs:eu-west-1:123456789012:service/demo/checkout",
                "123456789012",
                "eu-west-1",
                "demo",
                "ecs:service"),
            Map.of(),
            "Restart the task");

    store.claim(fingerprint, incidentId);

    assertThat(store.claim(ActionFingerprint.of(other, incidentId, 4L), incidentId)).isTrue();
  }

  @Test
  @DisplayName("under concurrency exactly one caller may execute")
  void concurrentClaimsElectOneWinner() throws Exception {
    int callers = 16;
    CountDownLatch startTogether = new CountDownLatch(1);
    AtomicInteger won = new AtomicInteger();

    try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
      List<? extends Future<?>> futures =
          java.util.stream.IntStream.range(0, callers)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            try {
                              // Release all threads at once to maximise the overlap; a
                              // read-then-write implementation fails precisely here.
                              startTogether.await();
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              return;
                            }
                            if (store.claim(fingerprint, incidentId)) {
                              won.incrementAndGet();
                            }
                          }))
              .toList();

      startTogether.countDown();
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    }

    assertThat(won.get())
        .as(
            "%d concurrent callers raced for one action; more than one winner would be a "
                + "duplicated production change",
            callers)
        .isEqualTo(1);

    Integer rows =
        jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE fingerprint = :fp")
            .param("fp", fingerprint.hex())
            .query(Integer.class)
            .single();
    assertThat(rows).isEqualTo(1);
  }
}
