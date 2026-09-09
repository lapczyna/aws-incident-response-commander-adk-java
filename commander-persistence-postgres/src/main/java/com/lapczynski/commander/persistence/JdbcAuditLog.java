package com.lapczynski.commander.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lapczynski.commander.application.port.AuditLog;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ActorRole;
import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.audit.AuditEventType;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit storage.
 *
 * <p>There is no update or delete method here, and the table refuses both through a trigger. The
 * two defences are deliberate duplication: the missing API stops honest mistakes, and the trigger
 * stops everything else, including a future maintainer who adds a "cleanup" query.
 *
 * <p>Uses Jackson 2 ({@code com.fasterxml.jackson}) rather than Spring Boot 4's Jackson 3 ({@code
 * tools.jackson}). Both are on the classpath by design — see docs/dependency-matrix.md — and this
 * class stays consistently on version 2 because it shares serialisation conventions with the
 * ADK-facing persistence code rather than with the REST layer.
 */
@Repository
public class JdbcAuditLog implements AuditLog {

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;

  public JdbcAuditLog(JdbcClient jdbc) {
    this.jdbc = jdbc;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  @Transactional
  public void append(AuditEvent event) {
    jdbc.sql(
            """
            INSERT INTO audit_events (incident_id, event_type, actor_id, summary, details,
                                      security_relevant, correlation_id, occurred_at)
            VALUES (:incidentId, :eventType, :actorId, :summary, CAST(:details AS jsonb),
                    :securityRelevant, :correlationId, :occurredAt)
            """)
        .param("incidentId", event.incidentId().value())
        .param("eventType", event.type().name())
        .param("actorId", event.actor().id())
        .param("summary", event.summary())
        .param("details", writeDetails(event.details()))
        .param("securityRelevant", event.isSecurityRelevant())
        .param("correlationId", event.correlationId().orElse(null))
        .param("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
        .update();
  }

  @Override
  public List<AuditEvent> findByIncident(IncidentId incidentId) {
    return jdbc.sql(
            """
            SELECT a.*, ac.display_name, ac.role
              FROM audit_events a
              JOIN actors ac ON ac.id = a.actor_id
             WHERE a.incident_id = :incidentId
             ORDER BY a.sequence
            """)
        .param("incidentId", incidentId.value())
        .query(this::mapEvent)
        .list();
  }

  @Override
  public List<AuditEvent> findSecurityRelevant(int limit) {
    return jdbc.sql(
            """
            SELECT a.*, ac.display_name, ac.role
              FROM audit_events a
              JOIN actors ac ON ac.id = a.actor_id
             WHERE a.security_relevant
             ORDER BY a.occurred_at DESC, a.sequence DESC
             LIMIT :limit
            """)
        .param("limit", limit)
        .query(this::mapEvent)
        .list();
  }

  private AuditEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
    Actor actor =
        new Actor(
            rs.getString("actor_id"),
            rs.getString("display_name"),
            ActorRole.valueOf(rs.getString("role")));

    return new AuditEvent(
        new IncidentId(rs.getObject("incident_id", UUID.class)),
        AuditEventType.valueOf(rs.getString("event_type")),
        actor,
        rs.getString("summary"),
        readDetails(rs.getString("details")),
        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
        Optional.ofNullable(rs.getString("correlation_id")));
  }

  private String writeDetails(Map<String, String> details) {
    try {
      return objectMapper.writeValueAsString(details);
    } catch (JsonProcessingException e) {
      // Details are a flat map of strings validated by AuditEvent, so this is unreachable in
      // practice. Failing loudly beats writing a partial audit entry.
      throw new IllegalStateException("could not serialise audit details", e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> readDetails(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, Map.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not read audit details", e);
    }
  }
}
