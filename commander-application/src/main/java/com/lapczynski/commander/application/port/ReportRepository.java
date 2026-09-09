package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.report.IncidentReport;
import java.util.Optional;

/**
 * Durable storage for generated incident reports.
 *
 * <p>Reports are versioned by generation time rather than overwritten. A report written while an
 * incident was still being remediated and one written after verification are different documents,
 * and losing the first would hide how the understanding changed.
 */
public interface ReportRepository {

  void save(IncidentReport report);

  /** The most recently generated report for an incident. */
  Optional<IncidentReport> findLatest(IncidentId incidentId);
}
