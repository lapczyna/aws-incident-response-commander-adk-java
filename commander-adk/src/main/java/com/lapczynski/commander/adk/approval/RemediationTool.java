package com.lapczynski.commander.adk.approval;

import com.google.adk.tools.Annotations.Schema;
import com.lapczynski.commander.application.ExecutionJournal;
import com.lapczynski.commander.application.port.IdempotencyStore;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.policy.PolicyDecision;
import com.lapczynski.commander.domain.policy.PolicyEngine;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ExecutedAction;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only tool in the system that can change anything.
 *
 * <p>Registered as a {@code LongRunningFunctionTool} with {@code requireConfirmation = true}, so
 * ADK pauses the invocation and asks for a human before this body ever runs.
 *
 * <p><strong>Human approval is necessary and not sufficient.</strong> By the time execution reaches
 * this method a person has already said yes, and it still performs three independent checks:
 *
 * <ol>
 *   <li><strong>Policy, again.</strong> The gate's verdict was formed before the human decided, and
 *       the world can move in between. Re-running the engine here means an approval cannot outlive
 *       the conditions that justified it, and it means no human can approve their way past the
 *       resource allowlist or the environment check.
 *   <li><strong>Idempotency.</strong> Executing is preceded by <em>claiming</em> the fingerprint. A
 *       replayed approval loses the claim and returns the stored prior result, so a duplicate
 *       cannot act twice even across instances.
 *   <li><strong>Dry-run.</strong> Unless real execution is explicitly enabled, the action is
 *       simulated and reported as simulated. The default is that nothing happens.
 * </ol>
 *
 * <p>The order matters. The claim is taken <em>before</em> acting and completed afterwards, so a
 * crash mid-action leaves an {@code IN_PROGRESS} claim that blocks a blind retry rather than
 * inviting one.
 */
public class RemediationTool {

  private static final Logger log = LoggerFactory.getLogger(RemediationTool.class);

  private final PolicyEngine policyEngine;
  private final IdempotencyStore idempotency;
  private final Map<String, String> targetTags;
  private final RemediationExecutor executor;
  private final Optional<ExecutionJournal> journal;
  private final Clock clock;

  /** Performs the action for real. Supplied so the demo, tests and AWS can differ. */
  @FunctionalInterface
  public interface RemediationExecutor {
    /**
     * @return a short description of what was done, for the incident record
     */
    String execute(ProposedAction action);
  }

  public RemediationTool(
      PolicyEngine policyEngine,
      IdempotencyStore idempotency,
      Map<String, String> targetTags,
      RemediationExecutor executor) {
    this(policyEngine, idempotency, targetTags, executor, null, Clock.systemUTC());
  }

  /**
   * @param journal records what was done, for the incident report. Optional because the tool must
   *     work in tests and demos that have no database; a missing journal costs a report section,
   *     never a safety property.
   */
  public RemediationTool(
      PolicyEngine policyEngine,
      IdempotencyStore idempotency,
      Map<String, String> targetTags,
      RemediationExecutor executor,
      ExecutionJournal journal,
      Clock clock) {
    this.policyEngine = policyEngine;
    this.idempotency = idempotency;
    this.targetTags = Map.copyOf(targetTags);
    this.executor = executor;
    this.journal = Optional.ofNullable(journal);
    this.clock = clock;
  }

  /**
   * Executes an approved remediation.
   *
   * <p>Takes the action as explicit parameters rather than reading it from session state, so that
   * what ADK shows the approver in the confirmation prompt is exactly what this method receives.
   * Reading from state would leave a gap between the two.
   */
  @Schema(
      description =
          "Execute an approved remediation action. This is the only tool that changes anything, "
              + "and it always requires human approval before it runs. Policy is re-checked at "
              + "execution time, so approval alone does not guarantee it will proceed.")
  public Map<String, Object> executeRemediation(
      @Schema(description = "The incident this action belongs to.") String incidentId,
      @Schema(description = "Incident version the approval was bound to.") Integer incidentVersion,
      @Schema(description = "One of the supported action types, for example ROLLBACK_DEPLOYMENT.")
          String actionType,
      @Schema(description = "Full ARN of the target resource.") String targetArn,
      @Schema(description = "AWS account id of the target.") String targetAccountId,
      @Schema(description = "AWS region of the target.") String targetRegion,
      @Schema(description = "Environment of the target, for example 'demo'.")
          String targetEnvironment,
      @Schema(description = "Confidence of the hypothesis motivating this action, 0.0 to 1.0.")
          Double confidence,
      @Schema(description = "One sentence describing what this does, for the approver.")
          String humanDescription) {

    ProposedAction action;
    try {
      action =
          new ProposedAction(
              ActionType.valueOf(actionType.trim().toUpperCase(java.util.Locale.ROOT)),
              new ResourceRef(
                  targetArn, targetAccountId, targetRegion, targetEnvironment, "ecs:service"),
              Map.of(),
              humanDescription == null || humanDescription.isBlank()
                  ? actionType + " against " + targetArn
                  : humanDescription);
    } catch (IllegalArgumentException e) {
      // An unparseable action at this point means the approval and the execution disagree about
      // what was authorised. Refusing is the only safe response.
      log.warn("Refusing malformed remediation request: {}", e.getMessage());
      return refusal("MALFORMED_ACTION", e.getMessage());
    }

    IncidentId incident;
    try {
      incident = IncidentId.of(incidentId);
    } catch (IllegalArgumentException e) {
      return refusal("MALFORMED_INCIDENT_ID", "incidentId is not a valid identifier");
    }

    // --- 1. Policy, re-evaluated at execution time -----------------------------------------
    PolicyDecision decision =
        policyEngine.evaluate(
            action,
            IncidentStatus.REMEDIATING,
            Confidence.clamped(confidence == null ? 0.0 : confidence),
            targetTags);

    if (decision instanceof PolicyDecision.Denied denied) {
      log.warn(
          "Refusing approved action at execution time: action={} target={} explanation={}",
          action.type(),
          action.target().arn(),
          denied.explanation());
      return refusal(
          "POLICY_DENIED_AT_EXECUTION",
          "This action was approved, but policy refuses it now: %s. Nothing has been changed."
              .formatted(denied.explanation()));
    }

    // --- 2. Idempotency: claim before acting -----------------------------------------------
    ActionFingerprint fingerprint =
        ActionFingerprint.of(action, incident, incidentVersion == null ? 0L : incidentVersion);

    if (!idempotency.claim(fingerprint, incident)) {
      Optional<IdempotencyStore.Record> prior = idempotency.find(fingerprint);
      log.info(
          "Duplicate execution refused: fingerprint={} priorOutcome={}",
          fingerprint.abbreviated(),
          prior.map(r -> r.outcome().name()).orElse("unknown"));

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("status", "already_executed");
      result.put("fingerprint", fingerprint.abbreviated());
      result.put("priorOutcome", prior.map(r -> r.outcome().name()).orElse("IN_PROGRESS"));
      result.put("priorResult", prior.map(IdempotencyStore.Record::result).orElse("{}"));
      result.put(
          "note",
          "This exact action has already been executed or is in progress. The previous result is "
              + "returned; nothing has been done a second time.");
      return result;
    }

    // --- 3. Execute, or simulate ------------------------------------------------------------
    boolean dryRun = policyEngine.isDryRun();
    Instant startedAt = clock.instant();
    try {
      String detail =
          dryRun
              ? "DRY RUN: %s would have been performed against %s. No change was made."
                  .formatted(action.type(), action.target().arn())
              : executor.execute(action);

      idempotency.complete(
          fingerprint, IdempotencyStore.Outcome.SUCCEEDED, "{\"detail\":\"%s\"}".formatted(detail));

      journal.ifPresent(
          j ->
              j.record(
                  incident,
                  fingerprint,
                  action,
                  dryRun,
                  ExecutedAction.Outcome.SUCCEEDED,
                  detail,
                  startedAt));

      log.info(
          "Remediation executed: action={} target={} dryRun={} fingerprint={}",
          action.type(),
          action.target().arn(),
          dryRun,
          fingerprint.abbreviated());

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("status", "executed");
      // Reported per execution rather than inferred from configuration, so a report can never
      // imply a real change was made when it was not.
      result.put("dryRun", dryRun);
      result.put("action", action.type().name());
      result.put("target", action.target().arn());
      result.put("fingerprint", fingerprint.abbreviated());
      result.put("detail", detail);
      return result;

    } catch (RuntimeException e) {
      // The claim is completed as FAILED rather than released. A released claim would invite a
      // blind retry of an action whose real-world effect is unknown.
      idempotency.complete(
          fingerprint,
          IdempotencyStore.Outcome.FAILED,
          "{\"error\":\"%s\"}".formatted(String.valueOf(e.getMessage())));

      // Recorded as prominently as a success. A failed action whose effect is unknown is the one
      // an operator most needs to find in the report.
      journal.ifPresent(
          j ->
              j.record(
                  incident,
                  fingerprint,
                  action,
                  dryRun,
                  ExecutedAction.Outcome.FAILED,
                  String.valueOf(e.getMessage()),
                  startedAt));

      log.error(
          "Remediation failed: action={} target={} error={}",
          action.type(),
          action.target().arn(),
          e.toString());

      return Map.of(
          "status",
          "failed",
          "action",
          action.type().name(),
          "detail",
          String.valueOf(e.getMessage()),
          "note",
          "The action failed partway through. Its effect on the target is unknown, so it will not "
              + "be retried automatically. Verify the current state before deciding what to do.");
    }
  }

  private static Map<String, Object> refusal(String reason, String detail) {
    return Map.of(
        "status", "refused", "reason", reason, "detail", detail, "note", "No change was made.");
  }
}
