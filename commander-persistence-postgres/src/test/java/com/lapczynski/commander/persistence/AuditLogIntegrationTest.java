package com.lapczynski.commander.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lapczynski.commander.application.port.AuditLog;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ActorRole;
import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.audit.AuditEventType;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The audit log's value depends entirely on being unfalsifiable, so the append-only guarantee is
 * tested against the real database rather than assumed from the absence of an update method.
 */
class AuditLogIntegrationTest extends PostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");
  private static final Actor APPROVER = new Actor("bob", "Bob", ActorRole.APPROVER);

  @Autowired private AuditLog auditLog;
  @Autowired private IncidentRepository incidents;
  @Autowired private JdbcClient jdbc;

  private IncidentId incidentId;

  @BeforeEach
  void setUp() {
    truncateIncidents(jdbc);
    jdbc.sql(
            "INSERT INTO actors (id, display_name, role) VALUES ('bob', 'Bob', 'APPROVER') "
                + "ON CONFLICT (id) DO NOTHING")
        .update();

    Incident incident =
        Incident.open(
            IncidentId.newId(),
            "Elevated 5xx on checkout",
            new ServiceRef("checkout", "demo"),
            Severity.SEV2,
            T0);
    incidents.create(incident, Actor.SYSTEM);
    incidentId = incident.id();
  }

  private AuditEvent event(AuditEventType type, String summary, Instant at) {
    return AuditEvent.of(incidentId, type, APPROVER, summary, Map.of("detail", "value"), at);
  }

  @Test
  @DisplayName("an entry round-trips with its details intact")
  void roundTrips() {
    AuditEvent original = event(AuditEventType.APPROVAL_GRANTED, "Bob approved the rollback", T0);

    auditLog.append(original);

    List<AuditEvent> stored = auditLog.findByIncident(incidentId);
    assertThat(stored).hasSize(1);
    assertThat(stored.getFirst().summary()).isEqualTo("Bob approved the rollback");
    assertThat(stored.getFirst().details()).containsEntry("detail", "value");
    assertThat(stored.getFirst().actor()).isEqualTo(APPROVER);
  }

  @Test
  @DisplayName("entries written in the same instant retain their true order")
  void sameInstantEntriesKeepInsertionOrder() {
    auditLog.append(event(AuditEventType.APPROVAL_REQUESTED, "first", T0));
    auditLog.append(event(AuditEventType.APPROVAL_GRANTED, "second", T0));
    auditLog.append(event(AuditEventType.ACTION_EXECUTED, "third", T0));

    assertThat(auditLog.findByIncident(incidentId))
        .as("ordering must not depend on the timestamp alone; these three share one instant")
        .extracting(AuditEvent::summary)
        .containsExactly("first", "second", "third");
  }

  @Test
  @DisplayName("the security-relevant view excludes routine entries")
  void securityViewIsFiltered() {
    auditLog.append(event(AuditEventType.EVIDENCE_RECORDED, "collected metrics", T0));
    auditLog.append(event(AuditEventType.APPROVAL_GRANTED, "approved", T0.plusSeconds(1)));
    auditLog.append(event(AuditEventType.ACTION_EXECUTED, "rolled back", T0.plusSeconds(2)));

    assertThat(auditLog.findSecurityRelevant(50))
        .extracting(AuditEvent::summary)
        .containsExactlyInAnyOrder("approved", "rolled back")
        .doesNotContain("collected metrics");
  }

  @Nested
  @DisplayName("append-only, enforced by the database")
  class AppendOnly {

    @Test
    @DisplayName("UPDATE on audit_events is rejected")
    void updateIsRejected() {
      auditLog.append(event(AuditEventType.APPROVAL_GRANTED, "Bob approved", T0));

      assertThatThrownBy(
              () -> jdbc.sql("UPDATE audit_events SET summary = 'nothing happened here'").update())
          .as("an audit trail the application can rewrite is not an audit trail")
          .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("DELETE on audit_events is rejected")
    void deleteIsRejected() {
      auditLog.append(event(AuditEventType.ACTION_EXECUTED, "rolled back production", T0));

      assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events").update())
          .as("the one moment deletion would matter is the moment something has gone wrong")
          .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("the entry survives a rejected tampering attempt unchanged")
    void entrySurvivesTampering() {
      auditLog.append(event(AuditEventType.APPROVAL_GRANTED, "Bob approved the rollback", T0));

      try {
        jdbc.sql("UPDATE audit_events SET summary = 'tampered'").update();
      } catch (RuntimeException expected) {
        // The trigger raised, which is the point of the previous test.
      }

      assertThat(auditLog.findByIncident(incidentId))
          .extracting(AuditEvent::summary)
          .containsExactly("Bob approved the rollback");
    }

    @Test
    @DisplayName("deletion succeeds only when maintenance intent is declared, in one transaction")
    void maintenanceHatchIsExplicitAndTransactionScoped() {
      auditLog.append(event(AuditEventType.APPROVAL_GRANTED, "Bob approved", T0));

      // Without the declaration, deletion is refused.
      assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events").update())
          .hasMessageContaining("append-only");

      // Declaring it in a separate statement is NOT enough. The setting is transaction-scoped and
      // the connection is in autocommit, so it expires before the delete is issued. This is the
      // failure mode that makes the hatch hard to open by accident.
      jdbc.sql("SET LOCAL app.audit_maintenance = 'on'").update();
      assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events").update())
          .as("a stray SET LOCAL on a pooled connection must not unlock a later transaction")
          .hasMessageContaining("append-only");

      // Declared within the same transaction as the delete, retention can do its job.
      jdbc.sql(
              """
              DO $$
              BEGIN
                  PERFORM set_config('app.audit_maintenance', 'on', true);
                  DELETE FROM audit_events;
              END
              $$
              """)
          .update();

      assertThat(auditLog.findByIncident(incidentId))
          .as("the escape hatch is real, not decorative")
          .isEmpty();
    }

    @Test
    @DisplayName("the AuditLog port offers no mutation method at all")
    void portExposesNoMutation() {
      List<String> mutators =
          java.util.Arrays.stream(AuditLog.class.getMethods())
              .map(java.lang.reflect.Method::getName)
              .filter(name -> name.startsWith("update") || name.startsWith("delete"))
              .toList();

      assertThat(mutators)
          .as("the missing API stops honest mistakes; the trigger stops everything else")
          .isEmpty();
    }
  }
}
