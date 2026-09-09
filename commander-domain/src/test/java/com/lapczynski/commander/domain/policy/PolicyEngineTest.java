package com.lapczynski.commander.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.policy.PolicyDecision.Rule;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import com.lapczynski.commander.domain.remediation.RiskLevel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The policy engine is the component that makes "an LLM cannot authorize a production change" a
 * fact rather than a claim, so it is tested as a security control.
 *
 * <p>Two properties matter more than the rest and are asserted directly: a default configuration
 * permits nothing, and every individual rule is capable of refusing on its own.
 */
class PolicyEngineTest {

  private static final String ACCOUNT = "123456789012";
  private static final String REGION = "eu-west-1";
  private static final String ENVIRONMENT = "demo";
  private static final String ALLOWED_ARN =
      "arn:aws:ecs:eu-west-1:123456789012:service/demo/checkout";
  private static final Map<String, String> VALID_TAGS =
      Map.of("Project", "aws-incident-response-commander");
  private static final Confidence HIGH = new Confidence(0.9);

  private static ResourceRef target(String arn, String account, String region, String environment) {
    return new ResourceRef(arn, account, region, environment, "ecs:service");
  }

  private static ProposedAction action(
      ActionType type, ResourceRef target, Map<String, String> args) {
    return new ProposedAction(type, target, args, "test action");
  }

  private static ProposedAction restartCheckout() {
    return action(
        ActionType.RESTART_ECS_TASK, target(ALLOWED_ARN, ACCOUNT, REGION, ENVIRONMENT), Map.of());
  }

  /** A configuration that permits exactly the happy-path action and nothing else. */
  private static PolicyConfiguration permissiveForTest() {
    return new PolicyConfiguration(
        true,
        true,
        ACCOUNT,
        REGION,
        ENVIRONMENT,
        Set.of(
            ActionType.RESTART_ECS_TASK, ActionType.SCALE_ECS_SERVICE, ActionType.RUN_HEALTH_CHECK),
        Set.of(ALLOWED_ARN),
        new PolicyConfiguration.TagRequirement("Project", "aws-incident-response-commander"),
        new Confidence(0.7),
        3);
  }

  private static List<Rule> rulesOf(PolicyDecision decision) {
    if (decision instanceof PolicyDecision.Denied denied) {
      return denied.violations().stream().map(PolicyDecision.Violation::rule).toList();
    }
    return List.of();
  }

  @Nested
  @DisplayName("safe defaults")
  class SafeDefaults {

    @Test
    @DisplayName("a default configuration refuses everything")
    void defaultsDenyAllActions() {
      PolicyEngine engine =
          new PolicyEngine(PolicyConfiguration.safeDefaults(ACCOUNT, REGION, ENVIRONMENT));

      SoftAssertions softly = new SoftAssertions();
      for (ActionType type : ActionType.values()) {
        PolicyDecision decision =
            engine.evaluate(
                action(type, target(ALLOWED_ARN, ACCOUNT, REGION, ENVIRONMENT), Map.of()),
                IncidentStatus.AWAITING_APPROVAL,
                HIGH,
                VALID_TAGS);
        softly
            .assertThat(decision.isAllowed())
            .as("%s must be refused by a default configuration", type)
            .isFalse();
      }
      softly.assertAll();
    }

    @Test
    @DisplayName("defaults are dry-run")
    void defaultsAreDryRun() {
      PolicyEngine engine =
          new PolicyEngine(PolicyConfiguration.safeDefaults(ACCOUNT, REGION, ENVIRONMENT));
      assertThat(engine.isDryRun()).isTrue();
    }

    @Test
    @DisplayName("the global kill switch alone is enough to refuse")
    void killSwitchDenies() {
      PolicyConfiguration disabled =
          new PolicyConfiguration(
              false,
              true,
              ACCOUNT,
              REGION,
              ENVIRONMENT,
              Set.of(ActionType.RESTART_ECS_TASK),
              Set.of(ALLOWED_ARN),
              new PolicyConfiguration.TagRequirement("Project", "aws-incident-response-commander"),
              new Confidence(0.7),
              3);

      PolicyDecision decision =
          new PolicyEngine(disabled)
              .evaluate(restartCheckout(), IncidentStatus.AWAITING_APPROVAL, HIGH, VALID_TAGS);

      assertThat(decision.isAllowed()).isFalse();
      assertThat(rulesOf(decision)).contains(Rule.ACTIONS_GLOBALLY_DISABLED);
    }
  }

  @Nested
  @DisplayName("each rule refuses independently")
  class IndividualRules {

    private final PolicyEngine engine = new PolicyEngine(permissiveForTest());

    @Test
    @DisplayName("the happy path is allowed, so the negative cases below are meaningful")
    void happyPathIsAllowed() {
      PolicyDecision decision =
          engine.evaluate(restartCheckout(), IncidentStatus.AWAITING_APPROVAL, HIGH, VALID_TAGS);

      assertThat(decision.isAllowed())
          .as("if this fails, every denial test below proves nothing")
          .isTrue();
    }

    @Test
    @DisplayName("an action outside the allowlist is refused")
    void nonAllowlistedActionDenied() {
      PolicyDecision decision =
          engine.evaluate(
              action(
                  ActionType.ROLLBACK_DEPLOYMENT,
                  target(ALLOWED_ARN, ACCOUNT, REGION, ENVIRONMENT),
                  Map.of()),
              IncidentStatus.AWAITING_APPROVAL,
              HIGH,
              VALID_TAGS);

      assertThat(rulesOf(decision)).contains(Rule.ACTION_NOT_ALLOWLISTED);
    }

    @Test
    @DisplayName("a resource in another account is refused")
    void wrongAccountDenied() {
      ResourceRef foreign =
          target(
              "arn:aws:ecs:eu-west-1:999999999999:service/demo/checkout",
              "999999999999",
              REGION,
              ENVIRONMENT);

      PolicyDecision decision =
          engine.evaluate(
              action(ActionType.RESTART_ECS_TASK, foreign, Map.of()),
              IncidentStatus.AWAITING_APPROVAL,
              HIGH,
              VALID_TAGS);

      assertThat(rulesOf(decision)).contains(Rule.ACCOUNT_MISMATCH);
    }

    @Test
    @DisplayName("a resource in another region is refused")
    void wrongRegionDenied() {
      ResourceRef foreign =
          target(
              "arn:aws:ecs:us-east-1:123456789012:service/demo/checkout",
              ACCOUNT,
              "us-east-1",
              ENVIRONMENT);

      PolicyDecision decision =
          engine.evaluate(
              action(ActionType.RESTART_ECS_TASK, foreign, Map.of()),
              IncidentStatus.AWAITING_APPROVAL,
              HIGH,
              VALID_TAGS);

      assertThat(rulesOf(decision)).contains(Rule.REGION_MISMATCH);
    }

    @Test
    @DisplayName("a production resource is refused by a demo-scoped deployment")
    void wrongEnvironmentDenied() {
      ResourceRef production = target(ALLOWED_ARN, ACCOUNT, REGION, "production");

      PolicyDecision decision =
          engine.evaluate(
              action(ActionType.RESTART_ECS_TASK, production, Map.of()),
              IncidentStatus.AWAITING_APPROVAL,
              HIGH,
              VALID_TAGS);

      assertThat(rulesOf(decision)).contains(Rule.ENVIRONMENT_MISMATCH);
    }

    @Test
    @DisplayName("an ARN outside the resource allowlist is refused")
    void nonAllowlistedResourceDenied() {
      ResourceRef other =
          target(
              "arn:aws:ecs:eu-west-1:123456789012:service/demo/payments",
              ACCOUNT,
              REGION,
              ENVIRONMENT);

      PolicyDecision decision =
          engine.evaluate(
              action(ActionType.RESTART_ECS_TASK, other, Map.of()),
              IncidentStatus.AWAITING_APPROVAL,
              HIGH,
              VALID_TAGS);

      assertThat(rulesOf(decision)).contains(Rule.RESOURCE_NOT_ALLOWLISTED);
    }

    @Test
    @DisplayName("a target missing the project tag is refused")
    void missingTagDenied() {
      PolicyDecision decision =
          engine.evaluate(
              restartCheckout(),
              IncidentStatus.AWAITING_APPROVAL,
              HIGH,
              Map.of("Project", "other"));

      assertThat(rulesOf(decision)).contains(Rule.MISSING_REQUIRED_TAG);
    }

    @Test
    @DisplayName("a target with no tags at all is refused")
    void noTagsDenied() {
      PolicyDecision decision =
          engine.evaluate(restartCheckout(), IncidentStatus.AWAITING_APPROVAL, HIGH, Map.of());

      assertThat(rulesOf(decision)).contains(Rule.MISSING_REQUIRED_TAG);
    }

    @Test
    @DisplayName("scaling beyond the configured ceiling is refused")
    void scaleAboveBoundDenied() {
      PolicyDecision decision =
          engine.evaluate(
              action(
                  ActionType.SCALE_ECS_SERVICE,
                  target(ALLOWED_ARN, ACCOUNT, REGION, ENVIRONMENT),
                  Map.of("desiredCount", "50")),
              IncidentStatus.AWAITING_APPROVAL,
              HIGH,
              VALID_TAGS);

      assertThat(rulesOf(decision)).contains(Rule.SCALE_OUT_OF_BOUNDS);
    }

    @Test
    @DisplayName("a non-numeric scale argument is refused rather than ignored")
    void nonNumericScaleDenied() {
      PolicyDecision decision =
          engine.evaluate(
              action(
                  ActionType.SCALE_ECS_SERVICE,
                  target(ALLOWED_ARN, ACCOUNT, REGION, ENVIRONMENT),
                  Map.of("desiredCount", "all of them")),
              IncidentStatus.AWAITING_APPROVAL,
              HIGH,
              VALID_TAGS);

      assertThat(rulesOf(decision)).contains(Rule.SCALE_OUT_OF_BOUNDS);
    }

    @Test
    @DisplayName("a low-confidence hypothesis cannot justify an action")
    void lowConfidenceDenied() {
      PolicyDecision decision =
          engine.evaluate(
              restartCheckout(), IncidentStatus.AWAITING_APPROVAL, new Confidence(0.2), VALID_TAGS);

      assertThat(rulesOf(decision)).contains(Rule.CONFIDENCE_BELOW_THRESHOLD);
    }

    @ParameterizedTest
    @EnumSource(
        value = IncidentStatus.class,
        names = {"AWAITING_APPROVAL", "REMEDIATING"},
        mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("no other incident status permits execution")
    void wrongStatusDenied(IncidentStatus status) {
      PolicyDecision decision = engine.evaluate(restartCheckout(), status, HIGH, VALID_TAGS);

      assertThat(rulesOf(decision))
          .as("executing an action while the incident is %s would bypass the approval gate", status)
          .contains(Rule.INCIDENT_STATUS_FORBIDS_EXECUTION);
    }
  }

  @Nested
  @DisplayName("denials report every failed rule")
  class ComprehensiveDenial {

    @Test
    @DisplayName("a proposal violating several rules lists all of them")
    void reportsAllViolations() {
      ResourceRef bad =
          target(
              "arn:aws:ecs:us-east-1:999999999999:service/prod/payments",
              "999999999999",
              "us-east-1",
              "production");

      PolicyDecision decision =
          new PolicyEngine(permissiveForTest())
              .evaluate(
                  action(ActionType.ROLLBACK_DEPLOYMENT, bad, Map.of("desiredCount", "99")),
                  IncidentStatus.INVESTIGATING,
                  new Confidence(0.1),
                  Map.of());

      assertThat(rulesOf(decision))
          .as("an operator should see the whole picture in one pass, not one rule at a time")
          .contains(
              Rule.ACTION_NOT_ALLOWLISTED,
              Rule.ACCOUNT_MISMATCH,
              Rule.REGION_MISMATCH,
              Rule.ENVIRONMENT_MISMATCH,
              Rule.RESOURCE_NOT_ALLOWLISTED,
              Rule.MISSING_REQUIRED_TAG,
              Rule.SCALE_OUT_OF_BOUNDS,
              Rule.CONFIDENCE_BELOW_THRESHOLD,
              Rule.INCIDENT_STATUS_FORBIDS_EXECUTION);
    }
  }

  @Nested
  @DisplayName("risk classification")
  class RiskClassification {

    private final PolicyEngine engine = new PolicyEngine(permissiveForTest());

    @Test
    @DisplayName("every state-changing action requires approval")
    void stateChangingRequiresApproval() {
      PolicyDecision decision =
          engine.evaluate(restartCheckout(), IncidentStatus.AWAITING_APPROVAL, HIGH, VALID_TAGS);

      assertThat(decision).isInstanceOf(PolicyDecision.Allowed.class);
      assertThat(((PolicyDecision.Allowed) decision).approvalRequired())
          .as("this is the whole point of the system")
          .isTrue();
    }

    @Test
    @DisplayName("a read-only health check does not require approval")
    void healthCheckNeedsNoApproval() {
      PolicyDecision decision =
          engine.evaluate(
              action(
                  ActionType.RUN_HEALTH_CHECK,
                  target(ALLOWED_ARN, ACCOUNT, REGION, ENVIRONMENT),
                  Map.of()),
              IncidentStatus.AWAITING_APPROVAL,
              HIGH,
              VALID_TAGS);

      assertThat(decision).isInstanceOf(PolicyDecision.Allowed.class);
      assertThat(((PolicyDecision.Allowed) decision).approvalRequired()).isFalse();
    }

    @Test
    @DisplayName("low confidence raises the risk of a state-changing action")
    void lowConfidenceEscalatesRisk() {
      ProposedAction restart = restartCheckout();

      RiskLevel confident = engine.assessRisk(restart, new Confidence(0.95));
      RiskLevel unsure = engine.assessRisk(restart, new Confidence(0.75));

      assertThat(confident).isEqualTo(RiskLevel.MEDIUM);
      assertThat(unsure)
          .as("acting on a shaky diagnosis is riskier than the action alone suggests")
          .isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("a low-confidence rollback is classified CRITICAL")
    void lowConfidenceRollbackIsCritical() {
      ProposedAction rollback =
          action(
              ActionType.ROLLBACK_DEPLOYMENT,
              target(ALLOWED_ARN, ACCOUNT, REGION, ENVIRONMENT),
              Map.of());

      assertThat(engine.assessRisk(rollback, new Confidence(0.5))).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    @DisplayName("risk only ever escalates, never softens")
    void riskNeverDecreases() {
      SoftAssertions softly = new SoftAssertions();
      for (ActionType type : ActionType.values()) {
        ProposedAction proposed =
            action(type, target(ALLOWED_ARN, ACCOUNT, REGION, ENVIRONMENT), Map.of());
        RiskLevel assessed = engine.assessRisk(proposed, Confidence.CERTAIN);

        softly
            .assertThat(assessed.compareTo(type.baselineRisk()))
            .as("%s assessed at %s, below its baseline %s", type, assessed, type.baselineRisk())
            .isGreaterThanOrEqualTo(0);
      }
      softly.assertAll();
    }
  }
}
