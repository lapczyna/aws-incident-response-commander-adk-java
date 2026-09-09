package com.lapczynski.commander.domain.policy;

import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.remediation.ActionType;
import java.util.Objects;
import java.util.Set;

/**
 * The deployment-specific limits the policy engine enforces.
 *
 * <p>Every field here is a deliberate narrowing of what the system is allowed to do. The defaults
 * are the safe ones: {@link #safeDefaults} disables actions entirely and allowlists nothing, so a
 * misconfigured or partially configured deployment refuses to act rather than acting broadly.
 *
 * @param actionsEnabled global kill switch. False means no action executes, whatever anyone
 *     approves.
 * @param dryRun when true, actions are simulated and their effects logged but never applied. This
 *     is the default, and turning it off is an explicit operational decision.
 * @param allowedActions the action types permitted in this deployment
 * @param allowedResourceArns exact ARNs actions may target. Empty means nothing is targetable.
 * @param requiredTag a tag key and value every target must carry, marking it as demo
 *     infrastructure. This is the backstop that keeps the system away from real workloads that
 *     happen to share an account.
 * @param minimumConfidence how sure the supporting hypothesis must be before any action is allowed
 * @param maxDesiredCount ceiling for scaling actions
 */
public record PolicyConfiguration(
    boolean actionsEnabled,
    boolean dryRun,
    String awsAccountId,
    String awsRegion,
    String environment,
    Set<ActionType> allowedActions,
    Set<String> allowedResourceArns,
    TagRequirement requiredTag,
    Confidence minimumConfidence,
    int maxDesiredCount) {

  /** A tag that every targetable resource must carry. */
  public record TagRequirement(String key, String value) {
    public TagRequirement {
      Objects.requireNonNull(key, "tag key must not be null");
      Objects.requireNonNull(value, "tag value must not be null");
      if (key.isBlank() || value.isBlank()) {
        throw new IllegalArgumentException("tag key and value must both be present");
      }
    }
  }

  public PolicyConfiguration {
    Objects.requireNonNull(awsAccountId, "awsAccountId must not be null");
    Objects.requireNonNull(awsRegion, "awsRegion must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    Objects.requireNonNull(allowedActions, "allowedActions must not be null");
    Objects.requireNonNull(allowedResourceArns, "allowedResourceArns must not be null");
    Objects.requireNonNull(requiredTag, "requiredTag must not be null");
    Objects.requireNonNull(minimumConfidence, "minimumConfidence must not be null");
    if (maxDesiredCount < 0) {
      throw new IllegalArgumentException("maxDesiredCount must not be negative");
    }
    allowedActions = Set.copyOf(allowedActions);
    allowedResourceArns = Set.copyOf(allowedResourceArns);
  }

  /**
   * A configuration that permits nothing.
   *
   * <p>Used as the starting point everywhere, including tests. If a test wants an action to succeed
   * it must say so explicitly, which means no test can accidentally execute something by inheriting
   * a permissive default.
   */
  public static PolicyConfiguration safeDefaults(
      String accountId, String region, String environment) {
    return new PolicyConfiguration(
        false,
        true,
        accountId,
        region,
        environment,
        Set.of(),
        Set.of(),
        new TagRequirement("Project", "aws-incident-response-commander"),
        new Confidence(0.7),
        1);
  }

  public boolean permitsAction(ActionType type) {
    return actionsEnabled && allowedActions.contains(type);
  }

  public boolean permitsResource(String arn) {
    return allowedResourceArns.contains(arn);
  }
}
