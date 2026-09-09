package com.lapczynski.commander.domain.hypothesis;

import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A candidate explanation for an incident, together with the evidence it rests on.
 *
 * <p>The {@code supportingEvidence} list is mandatory and must be non-empty. That single constraint
 * is what makes an unsupported hypothesis unrepresentable: a model cannot assert a cause here
 * without naming observations that exist in storage. The repository verifies the ids resolve; the
 * type guarantees that some were offered at all.
 *
 * <p>A hypothesis is refined by a bounded critique loop. Each pass produces a new {@link
 * HypothesisRevision} rather than editing this one, so the reasoning trail survives into the
 * postmortem — the interesting part of an investigation is often the explanation that was
 * discarded, and why.
 *
 * @param revision zero for the initial proposal, incremented by each refinement pass
 * @param contradictingEvidence observations that argue against this hypothesis. Recorded, not
 *     hidden: an explanation that ignores conflicting data is worth less than one that accounts for
 *     it.
 */
public record Hypothesis(
    HypothesisId id,
    IncidentId incidentId,
    int revision,
    String statement,
    String reasoning,
    Confidence confidence,
    List<EvidenceId> supportingEvidence,
    List<EvidenceId> contradictingEvidence,
    Instant createdAt) {

  public Hypothesis {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(incidentId, "incidentId must not be null");
    Objects.requireNonNull(statement, "statement must not be null");
    Objects.requireNonNull(reasoning, "reasoning must not be null");
    Objects.requireNonNull(confidence, "confidence must not be null");
    Objects.requireNonNull(supportingEvidence, "supportingEvidence must not be null");
    Objects.requireNonNull(contradictingEvidence, "contradictingEvidence must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    if (revision < 0) {
      throw new IllegalArgumentException("revision must not be negative, was " + revision);
    }
    if (statement.isBlank()) {
      throw new IllegalArgumentException("statement must not be blank");
    }
    if (supportingEvidence.isEmpty()) {
      throw new IllegalArgumentException(
          "a hypothesis must cite at least one piece of supporting evidence; "
              + "unsupported explanations are not representable in this system");
    }
    supportingEvidence = List.copyOf(supportingEvidence);
    contradictingEvidence = List.copyOf(contradictingEvidence);

    Set<EvidenceId> overlap = new java.util.LinkedHashSet<>(supportingEvidence);
    overlap.retainAll(contradictingEvidence);
    if (!overlap.isEmpty()) {
      throw new IllegalArgumentException(
          "evidence cannot both support and contradict the same hypothesis: " + overlap);
    }
  }

  /** Creates the initial, unrefined hypothesis. */
  public static Hypothesis initial(
      HypothesisId id,
      IncidentId incidentId,
      String statement,
      String reasoning,
      Confidence confidence,
      List<EvidenceId> supportingEvidence,
      Instant now) {
    return new Hypothesis(
        id, incidentId, 0, statement, reasoning, confidence, supportingEvidence, List.of(), now);
  }

  /**
   * Produces the next revision after a critique pass.
   *
   * <p>Returns a new instance sharing this hypothesis's identity, so the chain of revisions can be
   * reconstructed and shown in the report.
   */
  public Hypothesis refine(
      String newStatement,
      String newReasoning,
      Confidence newConfidence,
      List<EvidenceId> newSupporting,
      List<EvidenceId> newContradicting,
      Instant now) {
    return new Hypothesis(
        id,
        incidentId,
        revision + 1,
        newStatement,
        newReasoning,
        newConfidence,
        newSupporting,
        newContradicting,
        now);
  }

  /** Whether contradicting evidence exists that the hypothesis has not resolved. */
  public boolean isContested() {
    return !contradictingEvidence.isEmpty();
  }

  /**
   * Whether this hypothesis is solid enough to justify proposing remediation.
   *
   * <p>Contested hypotheses are held to a higher bar rather than blocked outright, because real
   * incidents rarely produce unanimous evidence. The threshold lives with the policy configuration;
   * this is the shape of the rule, not its tuning.
   */
  public boolean meetsThreshold(Confidence required) {
    Objects.requireNonNull(required, "required confidence must not be null");
    return confidence.atLeast(required);
  }
}
