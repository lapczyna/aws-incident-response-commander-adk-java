package com.lapczynski.commander.persistence;

import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.application.port.OptimisticLockException;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC-backed incident storage.
 *
 * <p>Hand-written SQL via {@link JdbcClient} rather than a mapped aggregate, so that the
 * optimistic-lock update is visibly a single conditional statement (ADR-0006). The whole safety
 * story rests on that update being atomic, and it should be readable as such.
 */
@Repository
public class JdbcIncidentRepository implements IncidentRepository {

  private final JdbcClient jdbc;

  public JdbcIncidentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void create(Incident incident, Actor openedBy) {
    jdbc.sql(
            """
            INSERT INTO incidents (id, title, service_name, environment, severity, status,
                                   version, received_at, updated_at, summary, closing_note)
            VALUES (:id, :title, :serviceName, :environment, :severity, :status,
                    :version, :receivedAt, :updatedAt, :summary, :closingNote)
            """)
        .param("id", incident.id().value())
        .param("title", incident.title())
        .param("serviceName", incident.affectedService().name())
        .param("environment", incident.affectedService().environment())
        .param("severity", incident.severity().name())
        .param("status", incident.status().name())
        .param("version", incident.version())
        .param(
            "receivedAt", OffsetDateTime.ofInstant(incident.receivedAt(), java.time.ZoneOffset.UTC))
        .param(
            "updatedAt", OffsetDateTime.ofInstant(incident.updatedAt(), java.time.ZoneOffset.UTC))
        .param("summary", incident.summary().orElse(null))
        .param("closingNote", incident.closingNote().orElse(null))
        .update();

    recordTransition(incident, null, openedBy, "incident opened");
  }

  @Override
  public Optional<Incident> findById(IncidentId id) {
    return jdbc.sql("SELECT * FROM incidents WHERE id = :id")
        .param("id", id.value())
        .query(JdbcIncidentRepository::mapIncident)
        .optional();
  }

  /**
   * {@inheritDoc}
   *
   * <p>The {@code WHERE version = :expectedVersion} clause is the lock. If a concurrent writer has
   * already moved this incident, the statement updates zero rows and we throw rather than
   * clobbering their change. Reading the version first and updating unconditionally would leave a
   * window in which two callers both believe they own the transition — and, since the version feeds
   * the approval fingerprint, that window is one in which an approval could be validated against
   * state that no longer exists.
   */
  @Override
  @Transactional
  public void update(Incident incident, long expectedVersion, Actor actor, String reason) {
    IncidentStatus previousStatus = loadStatus(incident.id());

    int rowsAffected =
        jdbc.sql(
                """
                UPDATE incidents
                   SET status = :status,
                       version = :version,
                       updated_at = :updatedAt,
                       summary = :summary,
                       closing_note = :closingNote
                 WHERE id = :id
                   AND version = :expectedVersion
                """)
            .param("status", incident.status().name())
            .param("version", incident.version())
            .param(
                "updatedAt",
                OffsetDateTime.ofInstant(incident.updatedAt(), java.time.ZoneOffset.UTC))
            .param("summary", incident.summary().orElse(null))
            .param("closingNote", incident.closingNote().orElse(null))
            .param("id", incident.id().value())
            .param("expectedVersion", expectedVersion)
            .update();

    if (rowsAffected == 0) {
      throw new OptimisticLockException(incident.id(), expectedVersion);
    }

    recordTransition(incident, previousStatus, actor, reason);
  }

  @Override
  public List<Incident> findOpen(int limit) {
    return jdbc.sql(
            """
            SELECT * FROM incidents
             WHERE status NOT IN ('RESOLVED', 'REJECTED', 'FAILED', 'CANCELLED')
             ORDER BY updated_at DESC
             LIMIT :limit
            """)
        .param("limit", limit)
        .query(JdbcIncidentRepository::mapIncident)
        .list();
  }

  @Override
  public List<Incident> findAwaitingApproval() {
    return jdbc.sql(
            """
            SELECT * FROM incidents
             WHERE status = 'AWAITING_APPROVAL'
             ORDER BY received_at
            """)
        .query(JdbcIncidentRepository::mapIncident)
        .list();
  }

  private IncidentStatus loadStatus(IncidentId id) {
    return jdbc.sql("SELECT status FROM incidents WHERE id = :id")
        .param("id", id.value())
        .query(String.class)
        .optional()
        .map(IncidentStatus::valueOf)
        .orElse(null);
  }

  private void recordTransition(
      Incident incident, IncidentStatus fromStatus, Actor actor, String reason) {
    jdbc.sql(
            """
            INSERT INTO incident_status_transitions
                   (incident_id, from_status, to_status, to_version, actor_id, reason, occurred_at)
            VALUES (:incidentId, :fromStatus, :toStatus, :toVersion, :actorId, :reason, :occurredAt)
            """)
        .param("incidentId", incident.id().value())
        .param("fromStatus", fromStatus == null ? null : fromStatus.name())
        .param("toStatus", incident.status().name())
        .param("toVersion", incident.version())
        .param("actorId", actor.id())
        .param("reason", reason)
        .param(
            "occurredAt", OffsetDateTime.ofInstant(incident.updatedAt(), java.time.ZoneOffset.UTC))
        .update();
  }

  private static Incident mapIncident(ResultSet rs, int rowNum) throws SQLException {
    return new Incident(
        new IncidentId(rs.getObject("id", java.util.UUID.class)),
        rs.getString("title"),
        new ServiceRef(rs.getString("service_name"), rs.getString("environment")),
        Severity.valueOf(rs.getString("severity")),
        IncidentStatus.valueOf(rs.getString("status")),
        rs.getLong("version"),
        toInstant(rs, "received_at"),
        toInstant(rs, "updated_at"),
        Optional.ofNullable(rs.getString("summary")),
        Optional.ofNullable(rs.getString("closing_note")));
  }

  private static Instant toInstant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
