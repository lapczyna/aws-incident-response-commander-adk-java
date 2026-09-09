package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import java.util.List;
import java.util.Optional;

/** Durable storage for the incident aggregate. */
public interface IncidentRepository {

  /** Persists a newly opened incident. */
  void create(Incident incident, Actor openedBy);

  Optional<Incident> findById(IncidentId id);

  /**
   * Who opened the incident.
   *
   * <p>Read back so separation of duties can be enforced: the person who raised an incident must
   * not be the one who approves acting on it. The opener is recorded on the first status
   * transition, and without a way to read it the rule can only be documented, not applied.
   *
   * <p>Empty when the incident exists but has no recorded opening transition, which should not
   * happen and is treated by callers as "cannot establish who opened this" rather than as "nobody
   * did".
   */
  Optional<Actor> openedBy(IncidentId id);

  /**
   * Persists a transitioned incident, recording the transition in the incident's history.
   *
   * <p>The update is conditional on the stored version still being {@code expectedVersion}. This is
   * the optimistic lock: if another writer moved the incident first, the update matches no rows and
   * this throws rather than overwriting.
   *
   * @param expectedVersion the version the caller believed it was updating
   * @throws OptimisticLockException if the stored version no longer matches
   */
  void update(Incident incident, long expectedVersion, Actor actor, String reason);

  /** Incidents that are not in a terminal status, most recently updated first. */
  List<Incident> findOpen(int limit);

  /**
   * Incidents stranded in {@link IncidentStatus#AWAITING_APPROVAL}.
   *
   * <p>Read on startup: these are the invocations that were waiting for a human when the process
   * stopped, and they are what resumption reattaches to.
   */
  List<Incident> findAwaitingApproval();
}
