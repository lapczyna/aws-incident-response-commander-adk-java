package com.lapczynski.commander.domain.evidence;

/** Where a piece of evidence came from. Each maps to one investigator agent and one port. */
public enum EvidenceSource {
  CLOUDWATCH_METRICS,
  CLOUDWATCH_LOGS,
  CLOUDWATCH_ALARMS,
  ECS_STATE,
  ECS_DEPLOYMENTS,
  CLOUDTRAIL_CHANGES,
  DEPLOYMENT_HISTORY,
  SERVICE_HEALTH,
  RUNBOOK,
  PRIOR_INCIDENT;

  /**
   * Whether content from this source can carry attacker-influenced text.
   *
   * <p>Log lines and CloudTrail entries can contain anything a caller managed to get written, up to
   * and including text shaped like instructions to a model. Metrics are numeric aggregates and
   * cannot. This distinction drives how aggressively content is sanitised and delimited before it
   * ever reaches a prompt.
   */
  public boolean carriesFreeText() {
    return switch (this) {
      case CLOUDWATCH_LOGS, CLOUDTRAIL_CHANGES, DEPLOYMENT_HISTORY, PRIOR_INCIDENT -> true;
      case CLOUDWATCH_METRICS, CLOUDWATCH_ALARMS, ECS_STATE, ECS_DEPLOYMENTS, SERVICE_HEALTH ->
          false;
      // Runbooks are authored internally and version-controlled, so they are trusted content.
      case RUNBOOK -> false;
    };
  }
}
