package com.lapczynski.commander.domain.policy;

import com.lapczynski.commander.domain.remediation.RiskLevel;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of evaluating a proposed action against policy.
 *
 * <p>A sealed interface rather than a boolean plus a message. Callers are forced by the compiler to
 * handle denial explicitly, and a denial always carries the specific reasons — which end up in the
 * audit log and the incident report, so "why was this refused" never needs guesswork.
 *
 * <p>Note there is no {@code ALLOW_WITHOUT_APPROVAL} for state-changing work. The only path that
 * skips approval is {@link Allowed#approvalRequired()} being false, which the engine grants solely
 * to read-only actions.
 */
public sealed interface PolicyDecision permits PolicyDecision.Allowed, PolicyDecision.Denied {

  /** Whether execution may proceed at all. */
  boolean isAllowed();

  /** Human-readable explanation, always populated. */
  String explanation();

  /**
   * The action passed every rule.
   *
   * @param approvalRequired whether a human must still authorise it. True for anything
   *     state-changing.
   * @param assessedRisk the risk level after applying contextual escalation
   */
  record Allowed(boolean approvalRequired, RiskLevel assessedRisk, String explanation)
      implements PolicyDecision {

    public Allowed {
      Objects.requireNonNull(assessedRisk, "assessedRisk must not be null");
      Objects.requireNonNull(explanation, "explanation must not be null");
      if (assessedRisk.requiresApproval() && !approvalRequired) {
        throw new IllegalArgumentException(
            "risk %s requires approval; a policy decision cannot waive it".formatted(assessedRisk));
      }
    }

    @Override
    public boolean isAllowed() {
      return true;
    }
  }

  /**
   * The action was refused, with every failed rule listed.
   *
   * <p>All violations are reported rather than just the first. An operator fixing a misconfigured
   * demo should see the whole picture in one pass, and a security reviewer should see every rule
   * that would have stopped an action, not merely the one that happened to run first.
   */
  record Denied(List<Violation> violations, String explanation) implements PolicyDecision {

    public Denied {
      Objects.requireNonNull(violations, "violations must not be null");
      Objects.requireNonNull(explanation, "explanation must not be null");
      if (violations.isEmpty()) {
        throw new IllegalArgumentException("a denial must state at least one violation");
      }
      violations = List.copyOf(violations);
    }

    @Override
    public boolean isAllowed() {
      return false;
    }
  }

  /** A single rule that the proposed action failed. */
  record Violation(Rule rule, String detail) {
    public Violation {
      Objects.requireNonNull(rule, "rule must not be null");
      Objects.requireNonNull(detail, "detail must not be null");
    }
  }

  /** The deterministic checks applied to every proposed action. */
  enum Rule {
    /** The action type is not in the allowlist for this deployment. */
    ACTION_NOT_ALLOWLISTED,
    /** The target resource is in a different AWS account. */
    ACCOUNT_MISMATCH,
    /** The target resource is in a different region. */
    REGION_MISMATCH,
    /** The target resource belongs to a different environment than the incident. */
    ENVIRONMENT_MISMATCH,
    /** The target ARN is not in the explicit resource allowlist. */
    RESOURCE_NOT_ALLOWLISTED,
    /** The target lacks the tag identifying it as part of this demo project. */
    MISSING_REQUIRED_TAG,
    /** A scaling argument fell outside the configured bounds. */
    SCALE_OUT_OF_BOUNDS,
    /** The incident is not in a status where executing an action is legitimate. */
    INCIDENT_STATUS_FORBIDS_EXECUTION,
    /** The supporting hypothesis did not reach the required confidence. */
    CONFIDENCE_BELOW_THRESHOLD,
    /** The configured budget for this incident was exhausted. */
    BUDGET_EXCEEDED,
    /** Global kill switch: all actions disabled. */
    ACTIONS_GLOBALLY_DISABLED
  }
}
