package com.lapczynski.commander.domain.incident;

/** Thrown when a caller attempts a lifecycle transition the state machine does not permit. */
public class IllegalTransitionException extends RuntimeException {

  private final IncidentStatus from;
  private final IncidentStatus to;

  public IllegalTransitionException(IncidentId incidentId, IncidentStatus from, IncidentStatus to) {
    super(
        "Incident %s cannot move from %s to %s; allowed: %s"
            .formatted(incidentId, from, to, from.allowedTransitions()));
    this.from = from;
    this.to = to;
  }

  public IncidentStatus from() {
    return from;
  }

  public IncidentStatus to() {
    return to;
  }
}
