package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.ApprovalDecision;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.approval.ApprovalStatus;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable storage for approval requests and the decisions taken on them.
 *
 * <p>The only mutation offered is a status transition. There is deliberately no way to edit a
 * request's action, target or fingerprint: an approval that could be amended after the fact would
 * mean the thing a human authorised and the thing that executes are not necessarily the same, which
 * is precisely what the fingerprint exists to prevent.
 */
public interface ApprovalRepository {

  /** Persists a new pending request. */
  void create(ApprovalRequest request);

  Optional<ApprovalRequest> findById(ApprovalId id);

  /** The outstanding request for an incident, if any. At most one may be pending at a time. */
  Optional<ApprovalRequest> findPendingForIncident(IncidentId incidentId);

  /** Everything currently awaiting a human, oldest first. */
  List<ApprovalRequest> findAllPending();

  /**
   * Requests that have passed their deadline while still pending.
   *
   * <p>Read by the expiry sweep. Expiry is evaluated on read as well, so a sweep that does not run
   * delays the status change rather than extending the window in which an approval can be used.
   */
  List<ApprovalRequest> findExpired(Instant now);

  /** Moves a request to a terminal status. */
  void updateStatus(ApprovalId id, ApprovalStatus status);

  /**
   * Records a decision.
   *
   * <p>The database enforces one decision per request, so a replayed approval fails here rather
   * than relying on the caller to have checked first.
   */
  void recordDecision(ApprovalDecision decision);

  Optional<ApprovalDecision> findDecision(ApprovalId id);

  /** Finds a request by its fingerprint, used when resuming after a restart. */
  Optional<ApprovalRequest> findByFingerprint(ActionFingerprint fingerprint);
}
