package com.lapczynski.commander.domain.incident;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The incident lifecycle, with every legal transition declared explicitly.
 *
 * <p>The transition table is expressed as a switch over {@code this}, so the compiler enforces that
 * a newly added status is given transitions. A map would have accepted silence.
 *
 * <p>Anything not listed here is illegal and throws. That matters more than it may look: the status
 * is what gates remediation, so a stray transition into {@link #REMEDIATING} would be a path to
 * executing an action nobody approved.
 */
public enum IncidentStatus {

  /** Alert accepted and persisted. Nothing has been investigated yet. */
  RECEIVED,

  /** Specialists are gathering evidence. Read-only; runs without human involvement. */
  INVESTIGATING,

  /** Evidence collected; hypothesis being formed and critiqued under a bounded loop. */
  FORMING_HYPOTHESIS,

  /** A cause is established well enough to propose remediation. */
  PLANNING_REMEDIATION,

  /**
   * A remediation proposal has passed the policy gate and needs human approval. The process may be
   * restarted while an incident sits here; all state is durable.
   */
  AWAITING_APPROVAL,

  /** An approved action is executing. */
  REMEDIATING,

  /** Remediation finished; checking whether the service actually recovered. */
  VERIFYING,

  /** Terminal: recovered, or found to need no action at all. */
  RESOLVED,

  /** Terminal: a human declined the proposed remediation, or the approval expired. */
  REJECTED,

  /** Terminal: the workflow could not complete. */
  FAILED,

  /** Terminal: cancelled by an operator. */
  CANCELLED;

  /**
   * Statuses reachable from this one.
   *
   * <p>Note that {@link #FORMING_HYPOTHESIS} may go straight to {@link #RESOLVED}: an investigation
   * that concludes there is no actionable incident is a success, not a failure, and must not be
   * pushed towards proposing remediation it does not believe in.
   */
  public Set<IncidentStatus> allowedTransitions() {
    return switch (this) {
      case RECEIVED -> unmodifiable(INVESTIGATING, CANCELLED, FAILED);
      case INVESTIGATING -> unmodifiable(FORMING_HYPOTHESIS, CANCELLED, FAILED);
      case FORMING_HYPOTHESIS -> unmodifiable(PLANNING_REMEDIATION, RESOLVED, CANCELLED, FAILED);
      case PLANNING_REMEDIATION -> unmodifiable(AWAITING_APPROVAL, RESOLVED, CANCELLED, FAILED);
      // No FAILED here: an incident awaiting a human decision is not failing, it is waiting.
      // Expiry is a rejection, which records that nobody decided in time.
      case AWAITING_APPROVAL -> unmodifiable(REMEDIATING, REJECTED, CANCELLED);
      // Once an action has started, cancellation is not offered: the action either completes and
      // gets verified, or it fails. Pretending it can be called off would be a lie about the world.
      case REMEDIATING -> unmodifiable(VERIFYING, FAILED);
      case VERIFYING -> unmodifiable(RESOLVED, FAILED);
      case RESOLVED, REJECTED, FAILED, CANCELLED -> Collections.emptySet();
    };
  }

  /** Whether this status admits no further transitions. */
  public boolean isTerminal() {
    return allowedTransitions().isEmpty();
  }

  /** Whether the incident is waiting on a human rather than on the system. */
  public boolean isAwaitingHuman() {
    return this == AWAITING_APPROVAL;
  }

  /**
   * Whether a state-changing action may be executing in this status. Used by the policy engine as a
   * second, independent check that execution is happening at a legitimate point in the lifecycle.
   */
  public boolean permitsActionExecution() {
    return this == REMEDIATING;
  }

  public boolean canTransitionTo(IncidentStatus target) {
    return allowedTransitions().contains(target);
  }

  private static Set<IncidentStatus> unmodifiable(IncidentStatus... statuses) {
    return Collections.unmodifiableSet(EnumSet.copyOf(Set.of(statuses)));
  }
}
