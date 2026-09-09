package com.lapczynski.commander.persistence;

import com.lapczynski.commander.application.port.ReportRepository;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.report.IncidentReport;
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
 * Stores generated reports.
 *
 * <p>Inserts rather than upserts. A report generated during remediation and one generated after
 * verification are different documents describing different states of knowledge, and overwriting
 * the first would erase the fact that the understanding changed — which is often the most
 * interesting thing in a postmortem.
 */
@Repository
public class JdbcReportRepository implements ReportRepository {

  private final JdbcClient jdbc;

  public JdbcReportRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void save(IncidentReport report) {
    UUID[] cited = report.citedEvidence().stream().map(EvidenceId::value).toArray(UUID[]::new);

    jdbc.sql(
            """
            INSERT INTO reports (id, incident_id, markdown, evidence_ids, generated_at)
            VALUES (:id, :incidentId, :markdown, :evidenceIds, :generatedAt)
            """)
        .param("id", report.id())
        .param("incidentId", report.incidentId().value())
        .param("markdown", report.markdown())
        .param("evidenceIds", cited)
        .param("generatedAt", OffsetDateTime.ofInstant(report.generatedAt(), ZoneOffset.UTC))
        .update();
  }

  @Override
  public Optional<IncidentReport> findLatest(IncidentId incidentId) {
    return jdbc.sql(
            """
            SELECT * FROM reports
             WHERE incident_id = :incidentId
             ORDER BY generated_at DESC
             LIMIT 1
            """)
        .param("incidentId", incidentId.value())
        .query(JdbcReportRepository::map)
        .optional();
  }

  private static IncidentReport map(ResultSet rs, int rowNum) throws SQLException {
    return new IncidentReport(
        rs.getObject("id", UUID.class),
        new IncidentId(rs.getObject("incident_id", UUID.class)),
        rs.getString("markdown"),
        citedEvidence(rs.getArray("evidence_ids")),
        rs.getObject("generated_at", OffsetDateTime.class).toInstant());
  }

  private static List<EvidenceId> citedEvidence(Array array) throws SQLException {
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
