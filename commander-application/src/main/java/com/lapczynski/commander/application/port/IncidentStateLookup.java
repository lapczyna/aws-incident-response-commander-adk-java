package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import java.util.Optional;

/**
 * Whether an incident exists, and what state it is in.
 *
 * <p>Narrow on purpose. The one component that needs this is the remediation tool, and handing that
 * tool an {@code IncidentRepository} would give the only code in the system that can change AWS the
 * ability to change the incident record as well — one call away from rewriting the thing it is
 * about to be judged against. This interface offers a single question and no way to answer it
 * wrongly.
 *
 * <p>Returns the status rather than the {@code Incident} for the same reason: the caller needs to
 * know whether execution is permitted from here, not what the incident says about itself.
 */
@FunctionalInterface
public interface IncidentStateLookup {

  /**
   * The incident's current status, or empty if there is no such incident.
   *
   * <p>Empty is a meaningful answer and not an error. A request naming an incident that does not
   * exist is a request whose authorisation cannot be established, and the caller is expected to
   * refuse rather than to throw.
   */
  Optional<IncidentStatus> statusOf(IncidentId incidentId);
}
