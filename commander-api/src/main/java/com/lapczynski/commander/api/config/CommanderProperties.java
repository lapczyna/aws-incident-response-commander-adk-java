package com.lapczynski.commander.api.config;

import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.policy.PolicyConfiguration;
import com.lapczynski.commander.domain.remediation.ActionType;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything this deployment is allowed to do, as configuration.
 *
 * <p>The defaults are the safe ones, and they are safe in the specific sense that a deployment
 * which configures nothing refuses everything: no actions enabled, dry run on, no action types
 * permitted, no resources targetable. A partially configured system therefore does nothing rather
 * than doing something broad, which is the failure direction that matters.
 *
 * <p>Bound as a record, so the values cannot be changed after start-up. A mutable configuration
 * object holding the action allowlist would be one setter away from being widened at runtime by
 * code that had no business doing so.
 */
@ConfigurationProperties("commander")
public record CommanderProperties(
    @DefaultValue Policy policy,
    @DefaultValue Investigation investigation,
    @DefaultValue Approval approval,
    @DefaultValue Demo demo) {

  /**
   * The limits the policy engine enforces.
   *
   * @param actionsEnabled global kill switch; false means nothing executes whatever anyone approves
   * @param dryRun when true, approved actions are simulated and reported as simulated
   * @param allowedActions the action types this deployment permits at all
   * @param allowedResourceArns the exact ARNs actions may target; empty means nothing is targetable
   */
  public record Policy(
      @DefaultValue("false") boolean actionsEnabled,
      @DefaultValue("true") boolean dryRun,
      @DefaultValue("123456789012") String awsAccountId,
      @DefaultValue("eu-west-1") String awsRegion,
      @DefaultValue("demo") String environment,
      @DefaultValue("0.7") double minimumConfidence,
      @DefaultValue("3") int maxDesiredCount,
      @DefaultValue RequiredTag requiredTag,
      @DefaultValue Set<ActionType> allowedActions,
      @DefaultValue Set<String> allowedResourceArns) {

    /** The tag every targetable resource must carry to be treated as demo infrastructure. */
    public record RequiredTag(
        @DefaultValue("Project") String key,
        @DefaultValue("aws-incident-response-commander") String value) {}

    /** Builds the domain's view of this configuration. */
    public PolicyConfiguration toDomain() {
      return new PolicyConfiguration(
          actionsEnabled,
          dryRun,
          awsAccountId,
          awsRegion,
          environment,
          allowedActions,
          allowedResourceArns,
          new PolicyConfiguration.TagRequirement(requiredTag.key(), requiredTag.value()),
          new Confidence(minimumConfidence),
          maxDesiredCount);
    }
  }

  /**
   * Bounds on a single investigation.
   *
   * @param maxToolCalls tool calls allowed per invocation, shared across the specialists
   * @param deadline wall-clock ceiling; an investigation that passes it reports what it has
   */
  public record Investigation(
      @DefaultValue("16") int maxToolCalls, @DefaultValue("PT5M") Duration deadline) {}

  /**
   * How long a human has.
   *
   * @param ttl after this, the request expires and cannot be approved. An approval with no deadline
   *     is one that can be redeemed against a system nobody remembers.
   */
  public record Approval(@DefaultValue("PT1H") Duration ttl) {}

  /**
   * Which simulated incident the demo serves.
   *
   * <p>Only read under the {@code simulator} profile. Under {@code aws} the signals come from AWS
   * and there is no scenario to choose.
   */
  public record Demo(@DefaultValue("latency-after-bad-deployment") String scenario) {}
}
