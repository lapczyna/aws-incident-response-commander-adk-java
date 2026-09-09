package com.lapczynski.commander.domain.policy;

import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.policy.PolicyDecision.Rule;
import com.lapczynski.commander.domain.policy.PolicyDecision.Violation;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import com.lapczynski.commander.domain.remediation.RiskLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The deterministic gate that decides whether a proposed action may execute.
 *
 * <p>This class is the reason the project can claim an LLM cannot authorize a production change.
 * There is no model here, no prompt, no natural language, and nothing an attacker can talk to. It
 * is a pure function from (proposal, configuration, context) to a decision, so its behaviour is
 * fully enumerable by tests.
 *
 * <p>It is invoked <strong>twice</strong> per action, by design (ADR-0007): once at the policy gate
 * before a human is asked, and again inside the tool body immediately before execution. The second
 * call is what stops an approval from outliving the conditions that justified it. Approval is
 * necessary; this engine is what makes it insufficient.
 *
 * <p>Rules are evaluated in full rather than short-circuiting, so a denial reports every reason.
 */
public final class PolicyEngine {

  /** Arguments understood as a scaling target, checked against {@code maxDesiredCount}. */
  private static final Set<String> DESIRED_COUNT_KEYS = Set.of("desiredCount", "desired_count");

  private final PolicyConfiguration configuration;

  public PolicyEngine(PolicyConfiguration configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
  }

  /**
   * Evaluates a proposed action.
   *
   * @param action what is being proposed
   * @param incidentStatus the incident's current status. Passed in rather than read from a
   *     repository so this stays a pure function.
   * @param hypothesisConfidence confidence of the hypothesis motivating the action
   * @param targetTags tags actually present on the target resource, as read from AWS. Never
   *     supplied by the model.
   */
  public PolicyDecision evaluate(
      ProposedAction action,
      IncidentStatus incidentStatus,
      Confidence hypothesisConfidence,
      Map<String, String> targetTags) {
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(incidentStatus, "incidentStatus must not be null");
    Objects.requireNonNull(hypothesisConfidence, "hypothesisConfidence must not be null");
    Objects.requireNonNull(targetTags, "targetTags must not be null");

    List<Violation> violations = new ArrayList<>();

    if (!configuration.actionsEnabled()) {
      violations.add(
          new Violation(
              Rule.ACTIONS_GLOBALLY_DISABLED,
              "actions are disabled for this deployment; nothing will execute"));
    }

    checkActionAllowlist(action.type(), violations);
    checkResourceScope(action.target(), violations);
    checkRequiredTag(targetTags, violations);
    checkScaleBounds(action, violations);
    checkConfidence(hypothesisConfidence, violations);

    // The status check is deliberately independent of the approval record. Even a valid approval
    // cannot make execution legitimate while the incident sits in, say, INVESTIGATING.
    if (!incidentStatus.permitsActionExecution() && !incidentStatus.isAwaitingHuman()) {
      violations.add(
          new Violation(
              Rule.INCIDENT_STATUS_FORBIDS_EXECUTION,
              "incident is %s; actions may only run from AWAITING_APPROVAL or REMEDIATING"
                  .formatted(incidentStatus)));
    }

    if (!violations.isEmpty()) {
      return new PolicyDecision.Denied(
          violations,
          "refused %s against %s: %d rule(s) failed"
              .formatted(action.type(), action.target().arn(), violations.size()));
    }

    RiskLevel risk = assessRisk(action, hypothesisConfidence);
    boolean approvalRequired = action.isStateChanging() || risk.requiresApproval();

    return new PolicyDecision.Allowed(
        approvalRequired,
        risk,
        "%s against %s permitted at risk %s%s"
            .formatted(
                action.type(),
                action.target().arn(),
                risk,
                approvalRequired ? ", pending human approval" : ""));
  }

  /**
   * Classifies risk for a permitted action.
   *
   * <p>Starts from the action type's baseline and escalates on context. Risk only ever moves
   * upwards: a confident model does not get to make a rollback safe.
   */
  public RiskLevel assessRisk(ProposedAction action, Confidence hypothesisConfidence) {
    RiskLevel risk = action.type().baselineRisk();

    // Acting on a shaky diagnosis is riskier than the action alone suggests.
    if (!hypothesisConfidence.atLeast(new Confidence(0.85)) && action.isStateChanging()) {
      risk = risk.escalatedTo(RiskLevel.HIGH);
    }

    // Anything aimed at production is at least high risk, whatever it is.
    if ("production".equalsIgnoreCase(action.target().environment()) && action.isStateChanging()) {
      risk = risk.escalatedTo(RiskLevel.HIGH);
    }

    // A rollback that is also low-confidence is the worst combination available here.
    if (action.type() == ActionType.ROLLBACK_DEPLOYMENT
        && !hypothesisConfidence.atLeast(new Confidence(0.7))) {
      risk = risk.escalatedTo(RiskLevel.CRITICAL);
    }

    return risk;
  }

  /** Whether this deployment executes for real, or only simulates. */
  public boolean isDryRun() {
    return configuration.dryRun();
  }

  private void checkActionAllowlist(ActionType type, List<Violation> violations) {
    if (!configuration.allowedActions().contains(type)) {
      violations.add(
          new Violation(
              Rule.ACTION_NOT_ALLOWLISTED,
              "%s is not in the allowed action set %s"
                  .formatted(type, configuration.allowedActions())));
    }
  }

  private void checkResourceScope(ResourceRef target, List<Violation> violations) {
    if (!configuration.awsAccountId().equals(target.accountId())) {
      violations.add(
          new Violation(
              Rule.ACCOUNT_MISMATCH,
              "target account %s is not the configured account %s"
                  .formatted(target.accountId(), configuration.awsAccountId())));
    }
    if (!configuration.awsRegion().equals(target.region())) {
      violations.add(
          new Violation(
              Rule.REGION_MISMATCH,
              "target region %s is not the configured region %s"
                  .formatted(target.region(), configuration.awsRegion())));
    }
    if (!configuration.environment().equalsIgnoreCase(target.environment())) {
      violations.add(
          new Violation(
              Rule.ENVIRONMENT_MISMATCH,
              "target environment %s is not the configured environment %s"
                  .formatted(target.environment(), configuration.environment())));
    }
    if (!configuration.permitsResource(target.arn())) {
      violations.add(
          new Violation(
              Rule.RESOURCE_NOT_ALLOWLISTED,
              "%s is not in the resource allowlist".formatted(target.arn())));
    }
  }

  private void checkRequiredTag(Map<String, String> targetTags, List<Violation> violations) {
    PolicyConfiguration.TagRequirement required = configuration.requiredTag();
    String actual = targetTags.get(required.key());
    if (!required.value().equals(actual)) {
      violations.add(
          new Violation(
              Rule.MISSING_REQUIRED_TAG,
              "target must carry tag %s=%s, found %s"
                  .formatted(
                      required.key(), required.value(), actual == null ? "no such tag" : actual)));
    }
  }

  private void checkScaleBounds(ProposedAction action, List<Violation> violations) {
    for (String key : DESIRED_COUNT_KEYS) {
      String raw = action.arguments().get(key);
      if (raw == null) {
        continue;
      }
      int requested;
      try {
        requested = Integer.parseInt(raw.trim());
      } catch (NumberFormatException e) {
        violations.add(
            new Violation(
                Rule.SCALE_OUT_OF_BOUNDS, "%s is not an integer: %s".formatted(key, raw)));
        continue;
      }
      if (requested < 0 || requested > configuration.maxDesiredCount()) {
        violations.add(
            new Violation(
                Rule.SCALE_OUT_OF_BOUNDS,
                "%s=%d is outside the permitted range [0, %d]"
                    .formatted(key, requested, configuration.maxDesiredCount())));
      }
    }
  }

  private void checkConfidence(Confidence hypothesisConfidence, List<Violation> violations) {
    if (!hypothesisConfidence.atLeast(configuration.minimumConfidence())) {
      violations.add(
          new Violation(
              Rule.CONFIDENCE_BELOW_THRESHOLD,
              "hypothesis confidence %.2f is below the required %.2f"
                  .formatted(
                      hypothesisConfidence.value(), configuration.minimumConfidence().value())));
    }
  }
}
