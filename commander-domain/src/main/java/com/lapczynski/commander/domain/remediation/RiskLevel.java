package com.lapczynski.commander.domain.remediation;

/** Risk classification for a proposed action, decided by deterministic rules, never by a model. */
public enum RiskLevel {
  NONE,
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL;

  /** The higher of two levels. Risk composes upwards only. */
  public RiskLevel escalatedTo(RiskLevel other) {
    return compareTo(other) >= 0 ? this : other;
  }

  /**
   * Whether this level requires explicit human approval.
   *
   * <p>Only {@link #NONE} is exempt, and only {@link
   * com.lapczynski.commander.domain.remediation.ActionType#RUN_HEALTH_CHECK} carries that level. In
   * practice every state-changing action is approval-gated.
   */
  public boolean requiresApproval() {
    return this != NONE;
  }
}
