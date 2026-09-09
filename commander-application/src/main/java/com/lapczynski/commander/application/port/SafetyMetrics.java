package com.lapczynski.commander.application.port;

import java.time.Duration;

/**
 * Counters for the decisions that matter.
 *
 * <p>A port rather than a direct Micrometer dependency, because the application module deliberately
 * knows nothing about frameworks and because the decisions counted here are the ones an operator
 * watches: how often policy refuses, how often an approval is turned down and why, and whether
 * remediations are actually working.
 *
 * <p>Every method is a no-op by default. Metrics must never be the reason a safety-critical path
 * fails, and a service constructed without a registry — in a test, or in a demo with no monitoring
 * — should behave identically to one with a registry attached.
 */
public interface SafetyMetrics {

  /** Discards everything. The default wherever a registry is not supplied. */
  SafetyMetrics NONE = new SafetyMetrics() {};

  /**
   * @param decision {@code ALLOWED} or {@code DENIED}
   */
  default void policyDecision(String decision) {}

  /**
   * @param outcome {@code APPROVED}, {@code REJECTED}, or the refusal reason
   */
  default void approvalDecision(String outcome) {}

  /** How long an approval request waited before a human decided it. */
  default void approvalWait(Duration waited) {}

  /**
   * @param outcome the {@code RecoveryVerification.Outcome} name
   */
  default void verificationOutcome(String outcome) {}

  /**
   * A model call whose provider reported no token usage.
   *
   * <p>Expected on the local and fake profiles, which genuinely report none. On a paid profile it
   * means spend is going untracked and the monthly budget is not seeing those calls, which is why
   * it is counted rather than ignored.
   */
  default void modelCallUntracked() {}
}
