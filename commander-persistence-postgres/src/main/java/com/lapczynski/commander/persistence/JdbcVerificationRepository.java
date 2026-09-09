package com.lapczynski.commander.persistence;

import com.lapczynski.commander.application.port.VerificationRepository;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.verification.RecoveryVerification;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores whether the fix worked, together with the numbers it was judged from.
 *
 * <p>{@code after_value} is written as {@code NaN} when the metric could not be read, rather than
 * as null. Both would round-trip, but null invites a reader — human or query — to treat it as zero,
 * and a zero latency reads as spectacular recovery.
 */
@Repository
public class JdbcVerificationRepository implements VerificationRepository {

  private final JdbcClient jdbc;

  public JdbcVerificationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void save(RecoveryVerification verification, Optional<UUID> executedActionId) {
    UUID[] evidenceIds =
        verification.supportingEvidence().stream().map(EvidenceId::value).toArray(UUID[]::new);

    jdbc.sql(
            """
            INSERT INTO verification_results (id, incident_id, executed_action_id, outcome,
                                              metric_name, before_value, after_value,
                                              recovery_threshold, summary, evidence_ids,
                                              verified_at)
            VALUES (:id, :incidentId, :executedActionId, :outcome, :metricName, :beforeValue,
                    :afterValue, :recoveryThreshold, :summary, :evidenceIds, :verifiedAt)
            """)
        .param("id", UUID.randomUUID())
        .param("incidentId", verification.incidentId().value())
        .param("executedActionId", executedActionId.orElse(null))
        .param("outcome", verification.outcome().name())
        .param("metricName", verification.metricName())
        .param("beforeValue", verification.beforeValue())
        .param("afterValue", verification.afterValue())
        .param("recoveryThreshold", verification.recoveryThreshold())
        .param("summary", verification.summary())
        .param("evidenceIds", evidenceIds)
        .param("verifiedAt", OffsetDateTime.ofInstant(verification.verifiedAt(), ZoneOffset.UTC))
        .update();
  }

  @Override
  public Optional<RecoveryVerification> findByIncident(IncidentId incidentId) {
    return jdbc.sql("SELECT * FROM verification_results WHERE incident_id = :incidentId")
        .param("incidentId", incidentId.value())
        .query(JdbcVerificationRepository::map)
        .optional();
  }

  private static RecoveryVerification map(ResultSet rs, int rowNum) throws SQLException {
    return new RecoveryVerification(
        new IncidentId(rs.getObject("incident_id", UUID.class)),
        rs.getString("metric_name"),
        rs.getDouble("before_value"),
        rs.getDouble("after_value"),
        rs.getDouble("recovery_threshold"),
        RecoveryVerification.Outcome.valueOf(rs.getString("outcome")),
        rs.getString("summary"),
        evidenceIds(rs.getArray("evidence_ids")),
        rs.getObject("verified_at", OffsetDateTime.class).toInstant());
  }

  private static List<EvidenceId> evidenceIds(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    try {
      return Arrays.stream((UUID[]) array.getArray()).map(EvidenceId::new).toList();
    } finally {
      array.free();
    }
  }
}
