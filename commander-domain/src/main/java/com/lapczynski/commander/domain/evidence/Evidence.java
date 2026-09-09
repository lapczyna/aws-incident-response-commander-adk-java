package com.lapczynski.commander.domain.evidence;

import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One observation collected during an investigation.
 *
 * <p>Evidence is the unit of accountability in this system. Every claim in the final report must
 * cite an {@link EvidenceId} that exists in storage, which is what makes "no unsupported claims" a
 * property that can be checked rather than hoped for.
 *
 * <p>Evidence is immutable and append-only. Investigators add; nothing edits or deletes. A
 * contradiction between two observations is represented as two pieces of evidence that disagree,
 * not as one overwriting the other — scenario 6 depends on the system being able to hold both.
 *
 * @param content the observation itself. This is <strong>untrusted data</strong> whenever {@link
 *     EvidenceSource#carriesFreeText()} is true: it may contain text engineered to look like
 *     instructions. It is never concatenated into a prompt without sanitisation and explicit
 *     delimiting.
 * @param collectedAt when the investigator recorded this, not when the underlying event happened
 * @param observedAt when the underlying event happened, where the source reports it
 */
public record Evidence(
    EvidenceId id,
    IncidentId incidentId,
    EvidenceSource source,
    String collectedBy,
    String summary,
    String content,
    Confidence confidence,
    Instant collectedAt,
    Optional<Instant> observedAt) {

  /** Upper bound on stored content, in characters. */
  public static final int MAX_CONTENT_LENGTH = 16_000;

  public Evidence {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(incidentId, "incidentId must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(collectedBy, "collectedBy must not be null");
    Objects.requireNonNull(summary, "summary must not be null");
    Objects.requireNonNull(content, "content must not be null");
    Objects.requireNonNull(confidence, "confidence must not be null");
    Objects.requireNonNull(collectedAt, "collectedAt must not be null");
    Objects.requireNonNull(observedAt, "observedAt must not be null");
    if (collectedBy.isBlank()) {
      throw new IllegalArgumentException("collectedBy must name the collecting agent or tool");
    }
    if (summary.isBlank()) {
      throw new IllegalArgumentException("summary must not be blank");
    }
    if (content.length() > MAX_CONTENT_LENGTH) {
      throw new IllegalArgumentException(
          "content exceeds %d characters (was %d); bound it at the tool boundary rather than "
                  .formatted(MAX_CONTENT_LENGTH, content.length())
              + "storing unbounded output");
    }
  }

  /**
   * Whether this evidence must be treated as untrusted input to a model.
   *
   * <p>Delegates to the source rather than inspecting the content, because deciding trust by
   * looking for suspicious text is exactly the game an attacker wins.
   */
  public boolean isUntrusted() {
    return source.carriesFreeText();
  }
}
