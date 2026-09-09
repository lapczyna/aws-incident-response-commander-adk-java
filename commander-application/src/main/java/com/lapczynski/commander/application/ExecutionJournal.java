package com.lapczynski.commander.application;

import com.lapczynski.commander.application.port.ApprovalRepository;
import com.lapczynski.commander.application.port.ExecutionRepository;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.remediation.ExecutedAction;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records what an execution did, after it has done it.
 *
 * <p>Separate from {@code IdempotencyStore} because the two answer different questions at different
 * moments. The store is consulted <em>before</em> acting and decides whether acting is permitted;
 * this is written <em>after</em> and is what a human reads. Sharing one table would have coupled
 * the concurrency mechanism to the reporting format.
 *
 * <p><strong>This must not throw into its caller.</strong> By the time it runs, the infrastructure
 * has already been changed. An exception here would propagate out of the remediation tool and be
 * reported to an operator as a failed action, which is both false and the more dangerous of the two
 * possible errors — it invites a retry of something that already succeeded. Failures are logged at
 * {@code ERROR} and swallowed.
 */
public class ExecutionJournal {

  private static final Logger log = LoggerFactory.getLogger(ExecutionJournal.class);

  private final ExecutionRepository executions;
  private final ApprovalRepository approvals;
  private final Clock clock;

  public ExecutionJournal(
      ExecutionRepository executions, ApprovalRepository approvals, Clock clock) {
    this.executions = executions;
    this.approvals = approvals;
    this.clock = clock;
  }

  /**
   * Records an attempt.
   *
   * @return the id of the stored record, or empty if it could not be stored
   */
  public Optional<UUID> record(
      IncidentId incidentId,
      ActionFingerprint fingerprint,
      ProposedAction action,
      boolean dryRun,
      ExecutedAction.Outcome outcome,
      String detail,
      Instant startedAt) {

    try {
      Optional<ApprovalId> approvalId =
          approvals.findByFingerprint(fingerprint).map(ApprovalRequest::id);

      if (approvalId.isEmpty()) {
        // Recorded anyway, and loudly. Something changed infrastructure; losing the row because
        // its approval cannot be found would leave no trace of it at all.
        log.error(
            "Executed action has no resolvable approval: incident={} fingerprint={} action={} "
                + "target={}. The execution is recorded without one; investigate how it reached "
                + "the executor.",
            incidentId,
            fingerprint.abbreviated(),
            action.type(),
            action.target().arn());
      }

      UUID id = UUID.randomUUID();
      executions.record(
          new ExecutedAction(
              id,
              incidentId,
              approvalId,
              fingerprint,
              action.type(),
              action.target().arn(),
              dryRun,
              outcome,
              Optional.ofNullable(detail),
              startedAt,
              Optional.of(clock.instant())));
      return Optional.of(id);

    } catch (RuntimeException e) {
      log.error(
          "Could not record executed action: incident={} fingerprint={} outcome={} error={}. "
              + "The action itself is unaffected; the audit log and idempotency record still "
              + "reflect it.",
          incidentId,
          fingerprint.abbreviated(),
          outcome,
          e.toString(),
          e);
      return Optional.empty();
    }
  }
}
