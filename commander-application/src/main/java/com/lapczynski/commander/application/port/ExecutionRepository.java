package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.remediation.ExecutedAction;
import java.util.List;
import java.util.Optional;

/**
 * The record of actions attempted against real infrastructure.
 *
 * <p>Append-only in practice, though not enforced by a trigger the way the audit log is: an
 * execution row is a fact about the past and there is no operation here that could revise one.
 */
public interface ExecutionRepository {

  void record(ExecutedAction action);

  List<ExecutedAction> findByIncident(IncidentId incidentId);

  /** The most recent attempt for an incident, which is what verification measures against. */
  Optional<ExecutedAction> findLatestForIncident(IncidentId incidentId);
}
