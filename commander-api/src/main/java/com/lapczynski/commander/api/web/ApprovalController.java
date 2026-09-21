package com.lapczynski.commander.api.web;

import com.lapczynski.commander.api.security.CurrentActor;
import com.lapczynski.commander.api.web.ApiModels.ApprovalView;
import com.lapczynski.commander.api.web.ApiModels.Decision;
import com.lapczynski.commander.api.web.ApiModels.IncidentView;
import com.lapczynski.commander.application.IncidentService;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ApprovalId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The human decision, over HTTP.
 *
 * <p>This is the endpoint the whole system is arranged around: two methods, both of which require
 * the approver role, and neither of which decides anything itself. {@code ApprovalService} performs
 * every check — role, separation of duties, replay, expiry, and the recomputed fingerprint that
 * catches an incident which moved since the request was raised — and throws if the decision cannot
 * be accepted. A controller that made any of those judgements would be a second place they could be
 * got wrong.
 *
 * <p><strong>Approving is not executing.</strong> The response describes what happened to the
 * incident afterwards, which may be that the action was refused at execution time by the same
 * policy engine that allowed it earlier. Approval is necessary and never sufficient, and this is
 * the surface where that most needs to be visible.
 */
@RestController
@RequestMapping("/api/approvals")
@Tag(name = "Approvals", description = "The human-in-the-loop gate")
public class ApprovalController {

  private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

  private final IncidentService incidents;
  private final CurrentActor currentActor;

  public ApprovalController(IncidentService incidents, CurrentActor currentActor) {
    this.incidents = incidents;
    this.currentActor = currentActor;
  }

  @GetMapping
  @Operation(
      summary = "Everything waiting for a decision",
      description =
          "Readable by anyone who can see incidents. Deciding needs the approver role; looking at "
              + "what is pending does not, and hiding the queue from responders would make the "
              + "system harder to operate without making it safer.")
  public List<ApprovalView> pending() {
    return incidents.pendingApprovals().stream().map(ApprovalView::of).toList();
  }

  @GetMapping("/{id}")
  @Operation(summary = "One request, with everything needed to decide it")
  public ApprovalView one(@PathVariable String id) {
    ApprovalId approvalId = ApprovalId.of(id);
    return incidents.pendingApprovals().stream()
        .filter(request -> request.id().equals(approvalId))
        .findFirst()
        .map(ApprovalView::of)
        .orElseThrow(
            () ->
                new java.util.NoSuchElementException(
                    "no pending approval %s; it may have been decided or expired".formatted(id)));
  }

  @PostMapping("/{id}/approve")
  @PreAuthorize("hasRole('APPROVER')")
  @Operation(
      summary = "Authorise the proposed action",
      description =
          "Records the decision, resumes the paused invocation, and carries the incident through "
              + "execution and verification. Returns the incident as it stands afterwards — which "
              + "may be FAILED, if the action was refused at execution time or recovery could not "
              + "be measured.")
  public IncidentView approve(
      @PathVariable String id, @Valid @RequestBody(required = false) Decision decision) {
    Actor approver = currentActor.get();
    log.info("Approval submitted: approvalId={} by={}", id, approver.id());

    return IncidentView.of(incidents.approve(ApprovalId.of(id), approver, comment(decision)));
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize("hasRole('APPROVER')")
  @Operation(
      summary = "Decline the proposed action",
      description =
          "Closes the incident as REJECTED and tells the paused invocation, so the agent records "
              + "the decision in its own trail rather than the run dangling unexplained.")
  public IncidentView reject(
      @PathVariable String id, @Valid @RequestBody(required = false) Decision decision) {
    Actor approver = currentActor.get();
    log.info("Rejection submitted: approvalId={} by={}", id, approver.id());

    return IncidentView.of(incidents.reject(ApprovalId.of(id), approver, comment(decision)));
  }

  /**
   * A comment is optional but never null.
   *
   * <p>The audit record stores what the approver said; storing the absence of a comment as an empty
   * string rather than a null keeps the report from printing "null" where a rationale should be.
   */
  private static String comment(Decision decision) {
    return decision == null || decision.comment() == null ? "" : decision.comment();
  }
}
