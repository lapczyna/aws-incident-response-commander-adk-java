package com.lapczynski.commander.persistence;

import com.lapczynski.commander.application.port.EvidenceRepository;
import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.evidence.Evidence;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.evidence.EvidenceSource;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores what was observed, and what could not be.
 *
 * <p>Reads are ordered by the generated {@code sequence} rather than by {@code collected_at}.
 * Timestamps tie — four specialists running in parallel routinely record within the same
 * millisecond — and a report that renumbered its citations between two reads of the same incident
 * would make the numbers in a printed postmortem wrong.
 *
 * <p>The {@code untrusted} column is written from {@link Evidence#isUntrusted()} rather than
 * recomputed on read. It is derived from the source either way, but persisting it means a query
 * that needs to find untrusted content does not have to know the rule, and a future change to the
 * rule cannot silently reclassify what was already stored and shown to a human.
 */
@Repository
public class JdbcEvidenceRepository implements EvidenceRepository {

  private final JdbcClient jdbc;

  public JdbcEvidenceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void save(Evidence evidence) {
    jdbc.sql(
            """
            INSERT INTO evidence (id, incident_id, source, collected_by, summary, content,
                                  confidence, untrusted, collected_at, observed_at)
            VALUES (:id, :incidentId, :source, :collectedBy, :summary, :content,
                    :confidence, :untrusted, :collectedAt, :observedAt)
            """)
        .param("id", evidence.id().value())
        .param("incidentId", evidence.incidentId().value())
        .param("source", evidence.source().name())
        .param("collectedBy", evidence.collectedBy())
        .param("summary", evidence.summary())
        .param("content", evidence.content())
        .param("confidence", evidence.confidence().value())
        .param("untrusted", evidence.isUntrusted())
        .param("collectedAt", OffsetDateTime.ofInstant(evidence.collectedAt(), ZoneOffset.UTC))
        .param(
            "observedAt",
            evidence
                .observedAt()
                .map(instant -> OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .orElse(null))
        .update();
  }

  @Override
  @Transactional
  public void saveGap(EvidenceGap gap) {
    jdbc.sql(
            """
            INSERT INTO evidence_gaps (incident_id, source, attempted_by, reason, detail,
                                       recorded_at)
            VALUES (:incidentId, :source, :attemptedBy, :reason, :detail, :recordedAt)
            """)
        .param("incidentId", gap.incidentId().value())
        .param("source", gap.source().name())
        .param("attemptedBy", gap.attemptedBy())
        .param("reason", gap.reason().name())
        .param("detail", gap.detail())
        .param("recordedAt", OffsetDateTime.ofInstant(gap.recordedAt(), ZoneOffset.UTC))
        .update();
  }

  @Override
  public List<Evidence> findByIncident(IncidentId incidentId) {
    return jdbc.sql(
            """
            SELECT * FROM evidence
             WHERE incident_id = :incidentId
             ORDER BY sequence
            """)
        .param("incidentId", incidentId.value())
        .query(JdbcEvidenceRepository::mapEvidence)
        .list();
  }

  @Override
  public List<EvidenceGap> findGapsByIncident(IncidentId incidentId) {
    return jdbc.sql(
            """
            SELECT * FROM evidence_gaps
             WHERE incident_id = :incidentId
             ORDER BY id
            """)
        .param("incidentId", incidentId.value())
        .query(JdbcEvidenceRepository::mapGap)
        .list();
  }

  private static Evidence mapEvidence(ResultSet rs, int rowNum) throws SQLException {
    OffsetDateTime observed = rs.getObject("observed_at", OffsetDateTime.class);

    return new Evidence(
        new EvidenceId(rs.getObject("id", java.util.UUID.class)),
        new IncidentId(rs.getObject("incident_id", java.util.UUID.class)),
        EvidenceSource.valueOf(rs.getString("source")),
        rs.getString("collected_by"),
        rs.getString("summary"),
        rs.getString("content"),
        new Confidence(rs.getDouble("confidence")),
        rs.getObject("collected_at", OffsetDateTime.class).toInstant(),
        Optional.ofNullable(observed).map(OffsetDateTime::toInstant));
  }

  private static EvidenceGap mapGap(ResultSet rs, int rowNum) throws SQLException {
    return new EvidenceGap(
        new IncidentId(rs.getObject("incident_id", java.util.UUID.class)),
        EvidenceSource.valueOf(rs.getString("source")),
        rs.getString("attempted_by"),
        EvidenceGap.Reason.valueOf(rs.getString("reason")),
        rs.getString("detail"),
        rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
  }
}
