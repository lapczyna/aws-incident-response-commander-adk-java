package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.incident.IncidentId;

/**
 * Thrown when a write is rejected because the incident changed since it was read.
 *
 * <p>This is not merely a concurrency nuisance. An incident whose version moved has, by definition,
 * invalidated every approval outstanding against it, so losing this race is the system correctly
 * refusing to act on a stale view.
 */
public class OptimisticLockException extends RuntimeException {

  public OptimisticLockException(IncidentId incidentId, long expectedVersion) {
    super(
        "Incident %s was modified concurrently; expected version %d"
            .formatted(incidentId, expectedVersion));
  }
}
