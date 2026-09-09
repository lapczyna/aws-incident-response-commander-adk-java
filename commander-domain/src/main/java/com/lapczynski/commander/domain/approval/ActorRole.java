package com.lapczynski.commander.domain.approval;

/** Roles recognised by the system, in increasing order of authority. */
public enum ActorRole {
  /** May read incidents, evidence and reports. */
  VIEWER,
  /** May open incidents and start investigations. Cannot approve. */
  INVESTIGATOR,
  /** May approve or reject remediation. */
  APPROVER;

  public boolean canApprove() {
    return this == APPROVER;
  }

  public boolean canInvestigate() {
    return this == INVESTIGATOR || this == APPROVER;
  }
}
