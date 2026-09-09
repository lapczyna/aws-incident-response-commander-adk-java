package com.lapczynski.commander.domain.remediation;

/**
 * The closed set of actions this system is capable of performing.
 *
 * <p>An enum, not a string. A model cannot invent a new action type: anything it proposes must map
 * onto one of these constants or fail to parse. This is the first and cheapest line of defence
 * against excessive agency, and it works before any policy rule is consulted.
 *
 * <p>Adding a constant here is a deliberate act that requires a policy rule, a risk classification
 * and an executor implementation, so the set stays small on purpose.
 */
public enum ActionType {

  /** Turn off a demo fault injected into the target service. */
  DEACTIVATE_DEMO_FAULT(RiskLevel.LOW),

  /** Restore a known-good configuration snapshot. */
  RESTORE_SAFE_CONFIGURATION(RiskLevel.MEDIUM),

  /** Run a health probe. Read-only, but modelled as an action because it is a deliberate step. */
  RUN_HEALTH_CHECK(RiskLevel.NONE),

  /** Restart or replace a demo ECS task. */
  RESTART_ECS_TASK(RiskLevel.MEDIUM),

  /** Change desired count within configured bounds. */
  SCALE_ECS_SERVICE(RiskLevel.MEDIUM),

  /** Roll back to a previously deployed task definition. */
  ROLLBACK_DEPLOYMENT(RiskLevel.HIGH);

  private final RiskLevel baselineRisk;

  ActionType(RiskLevel baselineRisk) {
    this.baselineRisk = baselineRisk;
  }

  /**
   * The floor risk for this action type.
   *
   * <p>Context can raise the risk of a specific proposal but never lower it below this. A rollback
   * is inherently high-risk regardless of how confident the model feels about it.
   */
  public RiskLevel baselineRisk() {
    return baselineRisk;
  }

  /** Whether this action changes state and therefore needs the full approval path. */
  public boolean isStateChanging() {
    return this != RUN_HEALTH_CHECK;
  }
}
