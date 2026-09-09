package com.lapczynski.commander.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lapczynski.commander.application.port.ApprovalRepository;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ActorRole;
import com.lapczynski.commander.domain.approval.ApprovalDecision;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.approval.ApprovalStatus;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import com.lapczynski.commander.domain.remediation.RiskLevel;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC storage for approvals.
 *
 * <p>The interesting part is what the database enforces rather than what this class does. A unique
 * constraint on {@code approval_decisions.approval_id} means a replayed approval fails at the
 * database, so exactly-once decision-making does not depend on the service having checked first or
 * on there being a single instance. A partial unique index allows at most one pending approval per
 * incident, which structurally prevents two proposals racing to be authorised.
 */
@Repository
public class JdbcApprovalRepository implements ApprovalRepository {

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public JdbcApprovalRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void create(ApprovalRequest request) {
    jdbc.sql(
            """
            INSERT INTO approval_requests
                   (id, incident_id, proposal_id, incident_version, fingerprint, risk, rationale,
                    expected_impact, supporting_evidence, status, requested_at, expires_at,
                    adk_function_call_id, action_type, target_arn, target_account_id,
                    target_region, target_environment, target_resource_type, arguments,
                    human_description)
            VALUES (:id, :incidentId, :proposalId, :incidentVersion, :fingerprint, :risk,
                    :rationale, :expectedImpact, :supportingEvidence, :status, :requestedAt,
                    :expiresAt, :adkFunctionCallId, :actionType, :targetArn, :targetAccountId,
                    :targetRegion, :targetEnvironment, :targetResourceType,
                    CAST(:arguments AS jsonb), :humanDescription)
            """)
        .param("id", request.id().value())
        .param("incidentId", request.incidentId().value())
        // Phase 5's planner does not persist a proposal row yet, so the approval carries the
        // action inline. Phase 8 links them; the column is nullable until then.
        .param("proposalId", null)
        .param("incidentVersion", request.incidentVersion())
        .param("fingerprint", request.fingerprint().hex())
        .param("risk", request.risk().name())
        .param("rationale", request.rationale())
        .param("expectedImpact", request.expectedImpact())
        .param("supportingEvidence", evidenceArray(request.supportingEvidence()))
        .param("status", request.status().name())
        .param("requestedAt", offset(request.requestedAt()))
        .param("expiresAt", offset(request.expiresAt()))
        .param("adkFunctionCallId", request.adkFunctionCallId().orElse(null))
        .param("actionType", request.action().type().name())
        .param("targetArn", request.action().target().arn())
        .param("targetAccountId", request.action().target().accountId())
        .param("targetRegion", request.action().target().region())
        .param("targetEnvironment", request.action().target().environment())
        .param("targetResourceType", request.action().target().resourceType())
        .param("arguments", writeJson(request.action().arguments()))
        .param("humanDescription", request.action().humanDescription())
        .update();
  }

  @Override
  public Optional<ApprovalRequest> findById(ApprovalId id) {
    return jdbc.sql("SELECT * FROM approval_requests WHERE id = :id")
        .param("id", id.value())
        .query(this::mapRequest)
        .optional();
  }

  @Override
  public Optional<ApprovalRequest> findPendingForIncident(IncidentId incidentId) {
    return jdbc.sql(
            "SELECT * FROM approval_requests WHERE incident_id = :incidentId AND status = 'PENDING'")
        .param("incidentId", incidentId.value())
        .query(this::mapRequest)
        .optional();
  }

  @Override
  public List<ApprovalRequest> findAllPending() {
    return jdbc.sql(
            "SELECT * FROM approval_requests WHERE status = 'PENDING' ORDER BY requested_at")
        .query(this::mapRequest)
        .list();
  }

  @Override
  public List<ApprovalRequest> findExpired(Instant now) {
    return jdbc.sql(
            "SELECT * FROM approval_requests WHERE status = 'PENDING' AND expires_at <= :now "
                + "ORDER BY expires_at")
        .param("now", offset(now))
        .query(this::mapRequest)
        .list();
  }

  @Override
  public Optional<ApprovalRequest> findByFingerprint(ActionFingerprint fingerprint) {
    return jdbc.sql("SELECT * FROM approval_requests WHERE fingerprint = :fingerprint")
        .param("fingerprint", fingerprint.hex())
        .query(this::mapRequest)
        .optional();
  }

  @Override
  @Transactional
  public void updateStatus(ApprovalId id, ApprovalStatus status) {
    jdbc.sql("UPDATE approval_requests SET status = :status WHERE id = :id")
        .param("status", status.name())
        .param("id", id.value())
        .update();
  }

  /**
   * {@inheritDoc}
   *
   * <p>A duplicate is translated rather than propagated, so callers see the domain's own "already
   * decided" rather than a database exception. The constraint is still what enforces it: this
   * method only makes the failure legible.
   */
  @Override
  @Transactional
  public void recordDecision(ApprovalDecision decision) {
    try {
      jdbc.sql(
              """
              INSERT INTO approval_decisions
                     (approval_id, decided_by, outcome, fingerprint_at_decision, comment, decided_at)
              VALUES (:approvalId, :decidedBy, :outcome, :fingerprint, :comment, :decidedAt)
              """)
          .param("approvalId", decision.approvalId().value())
          .param("decidedBy", decision.decidedBy().id())
          .param("outcome", decision.outcome().name())
          .param("fingerprint", decision.fingerprintAtDecision().hex())
          .param("comment", decision.comment().orElse(null))
          .param("decidedAt", offset(decision.decidedAt()))
          .update();

    } catch (DuplicateKeyException e) {
      throw new IllegalStateException(
          "approval %s already has a decision; a replayed approval must not execute twice"
              .formatted(decision.approvalId()),
          e);
    }
  }

  @Override
  public Optional<ApprovalDecision> findDecision(ApprovalId id) {
    return jdbc.sql(
            """
            SELECT d.*, a.display_name, a.role
              FROM approval_decisions d
              JOIN actors a ON a.id = d.decided_by
             WHERE d.approval_id = :id
            """)
        .param("id", id.value())
        .query(this::mapDecision)
        .optional();
  }

  // ---------------------------------------------------------------- mapping

  private ApprovalRequest mapRequest(ResultSet rs, int rowNum) throws SQLException {
    ProposedAction action =
        new ProposedAction(
            ActionType.valueOf(rs.getString("action_type")),
            new ResourceRef(
                rs.getString("target_arn"),
                rs.getString("target_account_id"),
                rs.getString("target_region"),
                rs.getString("target_environment"),
                rs.getString("target_resource_type")),
            readArguments(rs.getString("arguments")),
            rs.getString("human_description"));

    return new ApprovalRequest(
        new ApprovalId(rs.getObject("id", UUID.class)),
        new IncidentId(rs.getObject("incident_id", UUID.class)),
        rs.getLong("incident_version"),
        action,
        // trim(): the column is CHAR(64) and PostgreSQL pads it, which would otherwise fail
        // ActionFingerprint's length check on the way back in.
        ActionFingerprint.fromHex(rs.getString("fingerprint").trim()),
        RiskLevel.valueOf(rs.getString("risk")),
        rs.getString("rationale"),
        rs.getString("expected_impact"),
        readEvidence(rs.getArray("supporting_evidence")),
        ApprovalStatus.valueOf(rs.getString("status")),
        rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
        Optional.ofNullable(rs.getString("adk_function_call_id")));
  }

  private ApprovalDecision mapDecision(ResultSet rs, int rowNum) throws SQLException {
    return new ApprovalDecision(
        new ApprovalId(rs.getObject("approval_id", UUID.class)),
        new Actor(
            rs.getString("decided_by"),
            rs.getString("display_name"),
            ActorRole.valueOf(rs.getString("role"))),
        ApprovalDecision.Outcome.valueOf(rs.getString("outcome")),
        ActionFingerprint.fromHex(rs.getString("fingerprint_at_decision").trim()),
        rs.getObject("decided_at", OffsetDateTime.class).toInstant(),
        Optional.ofNullable(rs.getString("comment")));
  }

  private static List<EvidenceId> readEvidence(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    List<EvidenceId> ids = new ArrayList<>();
    for (Object value : (Object[]) array.getArray()) {
      ids.add(new EvidenceId(UUID.fromString(value.toString())));
    }
    return ids;
  }

  private static UUID[] evidenceArray(List<EvidenceId> evidence) {
    return evidence.stream().map(EvidenceId::value).toArray(UUID[]::new);
  }

  private String writeJson(Map<String, String> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("could not serialise action arguments", e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> readArguments(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, Map.class);
    } catch (Exception e) {
      throw new IllegalStateException("could not read action arguments", e);
    }
  }

  private static OffsetDateTime offset(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
