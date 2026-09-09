package com.lapczynski.commander.persistence;

import com.lapczynski.commander.application.port.ExecutionRepository;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ExecutedAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores what was attempted against real infrastructure. */
@Repository
public class JdbcExecutionRepository implements ExecutionRepository {

  private final JdbcClient jdbc;

  public JdbcExecutionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void record(ExecutedAction action) {
    jdbc.sql(
            """
            INSERT INTO executed_actions (id, incident_id, approval_id, fingerprint, action_type,
                                          target_arn, dry_run, outcome, detail, started_at,
                                          finished_at)
            VALUES (:id, :incidentId, :approvalId, :fingerprint, :actionType, :targetArn,
                    :dryRun, :outcome, :detail, :startedAt, :finishedAt)
            """)
        .param("id", action.id())
        .param("incidentId", action.incidentId().value())
        .param("approvalId", action.approvalId().map(ApprovalId::value).orElse(null))
        .param("fingerprint", action.fingerprint().hex())
        .param("actionType", action.actionType().name())
        .param("targetArn", action.targetArn())
        .param("dryRun", action.dryRun())
        .param("outcome", action.outcome().name())
        .param("detail", action.detail().orElse(null))
        .param("startedAt", OffsetDateTime.ofInstant(action.startedAt(), ZoneOffset.UTC))
        .param(
            "finishedAt",
            action
                .finishedAt()
                .map(instant -> OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .orElse(null))
        .update();
  }

  @Override
  public List<ExecutedAction> findByIncident(IncidentId incidentId) {
    return jdbc.sql(
            """
            SELECT * FROM executed_actions
             WHERE incident_id = :incidentId
             ORDER BY started_at
            """)
        .param("incidentId", incidentId.value())
        .query(JdbcExecutionRepository::map)
        .list();
  }

  @Override
  public Optional<ExecutedAction> findLatestForIncident(IncidentId incidentId) {
    return jdbc.sql(
            """
            SELECT * FROM executed_actions
             WHERE incident_id = :incidentId
             ORDER BY started_at DESC
             LIMIT 1
            """)
        .param("incidentId", incidentId.value())
        .query(JdbcExecutionRepository::map)
        .optional();
  }

  private static ExecutedAction map(ResultSet rs, int rowNum) throws SQLException {
    OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);

    return new ExecutedAction(
        rs.getObject("id", UUID.class),
        new IncidentId(rs.getObject("incident_id", UUID.class)),
        Optional.ofNullable(rs.getObject("approval_id", UUID.class)).map(ApprovalId::new),
        // CHAR(64) pads on read in some drivers; trim so the fingerprint round-trips exactly.
        ActionFingerprint.fromHex(rs.getString("fingerprint").trim()),
        ActionType.valueOf(rs.getString("action_type")),
        rs.getString("target_arn"),
        rs.getBoolean("dry_run"),
        ExecutedAction.Outcome.valueOf(rs.getString("outcome")),
        Optional.ofNullable(rs.getString("detail")),
        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
        Optional.ofNullable(finished).map(OffsetDateTime::toInstant));
  }
}
