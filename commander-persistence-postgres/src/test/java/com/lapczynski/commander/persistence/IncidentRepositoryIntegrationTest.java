package com.lapczynski.commander.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.application.port.OptimisticLockException;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ActorRole;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Verifies the incident aggregate survives a round trip, and that the optimistic lock works. */
class IncidentRepositoryIntegrationTest extends PostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");
  private static final Actor OPERATOR = new Actor("alice", "Alice", ActorRole.INVESTIGATOR);

  @Autowired private IncidentRepository repository;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void cleanUp() {
    truncateIncidents(jdbc);
    jdbc.sql(
            "INSERT INTO actors (id, display_name, role) VALUES ('alice', 'Alice', 'INVESTIGATOR') "
                + "ON CONFLICT (id) DO NOTHING")
        .update();
  }

  private Incident newIncident() {
    return Incident.open(
        IncidentId.newId(),
        "Elevated 5xx on checkout",
        new ServiceRef("checkout", "demo"),
        Severity.SEV2,
        T0);
  }

  @Test
  @DisplayName("an incident round-trips with every field intact")
  void roundTrips() {
    Incident original = newIncident();

    repository.create(original, OPERATOR);
    Incident loaded = repository.findById(original.id()).orElseThrow();

    assertThat(loaded).isEqualTo(original);
  }

  @Test
  @DisplayName("an optional summary round-trips as empty rather than null")
  void emptyOptionalsRoundTrip() {
    Incident original = newIncident();
    repository.create(original, OPERATOR);

    Incident loaded = repository.findById(original.id()).orElseThrow();

    assertThat(loaded.summary()).isEmpty();
    assertThat(loaded.closingNote()).isEmpty();
  }

  @Test
  @DisplayName("a missing incident yields an empty Optional, not an exception")
  void missingIncidentIsEmpty() {
    assertThat(repository.findById(IncidentId.newId())).isEmpty();
  }

  @Test
  @DisplayName("a transition is persisted and recorded in the incident's history")
  void transitionIsRecorded() {
    Incident incident = newIncident();
    repository.create(incident, OPERATOR);

    Incident investigating =
        incident.transitionTo(IncidentStatus.INVESTIGATING, T0.plusSeconds(10));
    repository.update(investigating, incident.version(), OPERATOR, "starting investigation");

    Incident loaded = repository.findById(incident.id()).orElseThrow();
    assertThat(loaded.status()).isEqualTo(IncidentStatus.INVESTIGATING);
    assertThat(loaded.version()).isEqualTo(1L);

    List<String> history =
        jdbc.sql(
                "SELECT to_status FROM incident_status_transitions "
                    + "WHERE incident_id = :id ORDER BY id")
            .param("id", incident.id().value())
            .query(String.class)
            .list();

    assertThat(history)
        .as("both the opening and the transition must appear in the incident's history")
        .containsExactly("RECEIVED", "INVESTIGATING");
  }

  @Nested
  @DisplayName("optimistic locking")
  class OptimisticLocking {

    @Test
    @DisplayName("an update against a stale version is refused")
    void staleUpdateIsRefused() {
      Incident incident = newIncident();
      repository.create(incident, OPERATOR);

      Incident first = incident.transitionTo(IncidentStatus.INVESTIGATING, T0.plusSeconds(10));
      repository.update(first, 0L, OPERATOR, "first writer wins");

      // A second writer still holding version 0 tries to move the same incident elsewhere.
      Incident stale = incident.transitionTo(IncidentStatus.CANCELLED, T0.plusSeconds(11));

      assertThatThrownBy(() -> repository.update(stale, 0L, OPERATOR, "second writer"))
          .isInstanceOf(OptimisticLockException.class)
          .hasMessageContaining("modified concurrently");

      assertThat(repository.findById(incident.id()).orElseThrow().status())
          .as("the losing writer must not have overwritten the winner")
          .isEqualTo(IncidentStatus.INVESTIGATING);
    }

    @Test
    @DisplayName("under concurrency exactly one writer wins")
    void concurrentUpdatesElectOneWinner() throws Exception {
      Incident incident = newIncident();
      repository.create(incident, OPERATOR);

      int writers = 8;
      AtomicInteger succeeded = new AtomicInteger();
      AtomicInteger rejected = new AtomicInteger();

      try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
        List<? extends Future<?>> futures =
            java.util.stream.IntStream.range(0, writers)
                .mapToObj(
                    i ->
                        pool.submit(
                            () -> {
                              Incident attempt =
                                  incident.transitionTo(
                                      IncidentStatus.INVESTIGATING, T0.plusSeconds(10 + i));
                              try {
                                repository.update(attempt, 0L, OPERATOR, "writer " + i);
                                succeeded.incrementAndGet();
                              } catch (OptimisticLockException expected) {
                                rejected.incrementAndGet();
                              }
                            }))
                .toList();

        for (Future<?> future : futures) {
          future.get(30, TimeUnit.SECONDS);
        }
      }

      assertThat(succeeded.get())
          .as(
              "exactly one concurrent writer may move the incident; more would mean two "
                  + "callers each believed they owned the transition")
          .isEqualTo(1);
      assertThat(rejected.get()).isEqualTo(writers - 1);
    }
  }

  @Nested
  @DisplayName("queries used at startup and in the console")
  class Queries {

    @Test
    @DisplayName("findOpen excludes terminal incidents")
    void findOpenExcludesTerminal() {
      Incident open = newIncident();
      repository.create(open, OPERATOR);

      Incident closed = newIncident();
      repository.create(closed, OPERATOR);
      Incident cancelled =
          closed.close(IncidentStatus.CANCELLED, "not a real incident", T0.plusSeconds(5));
      repository.update(cancelled, closed.version(), OPERATOR, "cancelled");

      assertThat(repository.findOpen(50))
          .extracting(Incident::id)
          .containsExactly(open.id())
          .doesNotContain(closed.id());
    }

    @Test
    @DisplayName("findAwaitingApproval finds incidents stranded by a restart")
    void findAwaitingApprovalFindsStrandedIncidents() {
      Incident incident = newIncident();
      repository.create(incident, OPERATOR);

      Incident awaiting =
          incident
              .transitionTo(IncidentStatus.INVESTIGATING, T0.plusSeconds(1))
              .transitionTo(IncidentStatus.FORMING_HYPOTHESIS, T0.plusSeconds(2))
              .transitionTo(IncidentStatus.PLANNING_REMEDIATION, T0.plusSeconds(3))
              .transitionTo(IncidentStatus.AWAITING_APPROVAL, T0.plusSeconds(4));

      // Persist each step the way the application would, so versions stay consistent.
      long version = incident.version();
      for (IncidentStatus status :
          List.of(
              IncidentStatus.INVESTIGATING,
              IncidentStatus.FORMING_HYPOTHESIS,
              IncidentStatus.PLANNING_REMEDIATION,
              IncidentStatus.AWAITING_APPROVAL)) {
        Incident current = repository.findById(incident.id()).orElseThrow();
        Incident next = current.transitionTo(status, T0.plusSeconds(version + 1));
        repository.update(next, current.version(), OPERATOR, "advance");
        version = next.version();
      }

      assertThat(repository.findAwaitingApproval())
          .as("this query is what lets a restarted process reattach to a waiting approval")
          .extracting(Incident::id)
          .containsExactly(awaiting.id());
    }
  }
}
