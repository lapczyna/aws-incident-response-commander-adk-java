package com.lapczynski.commander.domain.report;

import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A generated incident report.
 *
 * <p>The Markdown is stored alongside the ids of the evidence it cites. Storing the citation list
 * separately from the prose is what lets a reader — or a test — check that a report references
 * observations that exist, rather than taking the bracketed numbers in the text at face value.
 *
 * @param citedEvidence the observations the report was built from, in citation order. Position
 *     {@code n} in this list is what the text calls {@code [E(n+1)]}.
 */
public record IncidentReport(
    UUID id,
    IncidentId incidentId,
    String markdown,
    List<EvidenceId> citedEvidence,
    Instant generatedAt) {

  /** Upper bound on a stored report, in characters. */
  public static final int MAX_LENGTH = 200_000;

  public IncidentReport {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(incidentId, "incidentId must not be null");
    Objects.requireNonNull(markdown, "markdown must not be null");
    Objects.requireNonNull(generatedAt, "generatedAt must not be null");
    Objects.requireNonNull(citedEvidence, "citedEvidence must not be null");

    if (markdown.isBlank()) {
      throw new IllegalArgumentException("a report must have content");
    }
    if (markdown.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "report exceeds %d characters (was %d)".formatted(MAX_LENGTH, markdown.length()));
    }
    citedEvidence = List.copyOf(citedEvidence);
  }

  public static IncidentReport of(
      IncidentId incidentId, String markdown, List<EvidenceId> citedEvidence, Instant generatedAt) {
    return new IncidentReport(UUID.randomUUID(), incidentId, markdown, citedEvidence, generatedAt);
  }
}
