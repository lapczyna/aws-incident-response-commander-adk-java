package com.lapczynski.commander.domain.approval;

/** Lifecycle of a single approval request. */
public enum ApprovalStatus {
  /** Awaiting a human decision. */
  PENDING,
  /** A human approved it. Does not by itself authorise execution; the policy gate runs again. */
  APPROVED,
  /** A human declined it. */
  REJECTED,
  /** Nobody decided before the deadline. */
  EXPIRED,
  /**
   * The incident changed materially, so the recomputed fingerprint no longer matches. The approval
   * is void whether or not a human had already approved it.
   */
  SUPERSEDED,
  /** The incident was cancelled while this request was outstanding. */
  CANCELLED;

  public boolean isTerminal() {
    return this != PENDING;
  }

  /** Whether this status permits proceeding to execution. */
  public boolean permitsExecution() {
    return this == APPROVED;
  }
}
