package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.util.Optional;

/**
 * Exactly-once execution, keyed on the action fingerprint.
 *
 * <p>The contract deliberately makes <em>claiming</em> the right to execute a separate, atomic step
 * from recording the result. A caller must win {@link #claim} before it acts. Because the claim is
 * an insert against a primary key, the database decides the winner, so correctness does not depend
 * on application-level locking, on callers being well-behaved, or on there being only one process.
 *
 * <p>This is what makes "a duplicate approval does not execute twice" true even when two requests
 * arrive simultaneously on two instances.
 */
public interface IdempotencyStore {

  /** The state of a fingerprint that has already been seen. */
  enum Outcome {
    /** Claimed by someone, not yet finished. A second caller must not proceed. */
    IN_PROGRESS,
    SUCCEEDED,
    FAILED
  }

  /** A previously recorded execution. */
  record Record(ActionFingerprint fingerprint, Outcome outcome, String result) {}

  /**
   * Attempts to claim the right to execute this action.
   *
   * @return {@code true} if the caller now owns execution; {@code false} if the fingerprint was
   *     already claimed, in which case the caller must not act and should return the stored result
   *     from {@link #find}.
   */
  boolean claim(ActionFingerprint fingerprint, IncidentId incidentId);

  /** Records the outcome of an execution the caller previously claimed. */
  void complete(ActionFingerprint fingerprint, Outcome outcome, String result);

  /** The stored record for a fingerprint, if any. */
  Optional<Record> find(ActionFingerprint fingerprint);
}
