package com.lapczynski.commander.api.web;

import com.lapczynski.commander.api.web.ApiModels.ApiError;
import com.lapczynski.commander.application.port.ApprovalException;
import com.lapczynski.commander.application.port.OptimisticLockException;
import com.lapczynski.commander.domain.incident.IllegalTransitionException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns refusals into status codes.
 *
 * <p>The mapping is the interesting part, and it is chosen so that a client can tell the three
 * cases apart without reading prose: <strong>403</strong> means you are not allowed to decide this,
 * <strong>409</strong> means the decision is no longer the one being offered, and
 * <strong>404</strong> means it never was. Collapsing them into a single 400 would hide the
 * distinction that matters most — a stale approval and an unauthorised approver are very different
 * events, and only one of them is a security signal.
 *
 * <p>Nothing here decides anything either. Every case below is a refusal that already happened
 * further in, recorded in the audit log before it was thrown.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /**
   * The approval refusals, each mapped on its own.
   *
   * <p>A sealed hierarchy is what makes this exhaustive: adding a refusal reason to {@code
   * ApprovalException} without deciding what it means over HTTP will not compile.
   */
  @ExceptionHandler(ApprovalException.Rejected.class)
  public ResponseEntity<ApiError> rejected(ApprovalException.Rejected rejected) {
    ApprovalException reason = rejected.reason();

    return switch (reason) {
      case ApprovalException.NotFound notFound ->
          problem(
              HttpStatus.NOT_FOUND,
              "approval-not-found",
              "No such approval request",
              "There is no approval request %s.".formatted(notFound.approvalId()));

      case ApprovalException.NotAuthorised notAuthorised ->
          problem(
              HttpStatus.FORBIDDEN,
              "not-authorised",
              "Not authorised to decide this",
              notAuthorised.reason());

      // The incident moved since the request was raised, so the approver would be authorising
      // something other than what they were shown. A client should re-read the incident, not retry.
      case ApprovalException.Stale stale ->
          problem(
              HttpStatus.CONFLICT,
              "approval-stale",
              "The incident has changed since this was proposed",
              "This approval was bound to %s; the action now fingerprints as %s. Re-read the "
                      .formatted(stale.expectedFingerprint(), stale.actualFingerprint())
                  + "incident before deciding again.");

      case ApprovalException.Expired expired ->
          problem(
              HttpStatus.CONFLICT,
              "approval-expired",
              "This request has expired",
              "Approval %s passed its deadline without a decision."
                  .formatted(expired.approvalId()));

      case ApprovalException.AlreadyDecided decided ->
          problem(
              HttpStatus.CONFLICT,
              "approval-already-decided",
              "Already decided",
              "Approval %s is %s. A second decision is refused, and nothing was executed twice."
                  .formatted(decided.approvalId(), decided.existingOutcome()));
    };
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ApiError> notFound(NoSuchElementException e) {
    return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e.getMessage());
  }

  /**
   * A transition the state machine forbids.
   *
   * <p>409 rather than 400: the request was well formed and would have been valid a moment ago. The
   * message names both statuses, because "cannot cancel" is unhelpful and "cannot cancel an
   * incident that is REMEDIATING" is an explanation.
   */
  @ExceptionHandler(IllegalTransitionException.class)
  public ResponseEntity<ApiError> illegalTransition(IllegalTransitionException e) {
    return problem(
        HttpStatus.CONFLICT, "illegal-transition", "Not possible from here", e.getMessage());
  }

  @ExceptionHandler(OptimisticLockException.class)
  public ResponseEntity<ApiError> concurrentModification(OptimisticLockException e) {
    return problem(
        HttpStatus.CONFLICT,
        "concurrent-modification",
        "Someone else changed this first",
        "The incident was modified concurrently. Nothing was written; re-read it and try again.");
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiError> illegalState(IllegalStateException e) {
    return problem(HttpStatus.CONFLICT, "invalid-state", "Not possible from here", e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> illegalArgument(IllegalArgumentException e) {
    return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("The request body is not valid.");

    return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", detail);
  }

  /**
   * An authorisation refusal, which is not a failure.
   *
   * <p>Declared explicitly because the catch-all below would otherwise take it. A
   * {@code @ControllerAdvice} that handles {@code Exception} sees {@link AccessDeniedException}
   * before Spring Security's {@code ExceptionTranslationFilter} ever gets the chance to translate
   * it into a 403 — so every {@code @PreAuthorize} refusal is reported as a server error, and the
   * log fills with stack traces for a control working exactly as intended.
   *
   * <p>A viewer who tries to approve a remediation should be told they are not allowed. Telling
   * them the server broke invites them to try again, and invites whoever reads the log to go
   * looking for a fault that is not there.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> forbidden(AccessDeniedException e) {
    return problem(
        HttpStatus.FORBIDDEN,
        "forbidden",
        "Not permitted",
        "Your role does not permit this. Authorising a remediation needs an approver, and never "
            + "the actor who opened the incident.");
  }

  /**
   * The catch-all, and the only handler that does not say what went wrong.
   *
   * <p>An unexpected exception's message can carry an ARN, an account id, a SQL fragment or a
   * provider's error text. The detail goes to the log, where it is needed; the response says that
   * something failed and nothing else.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(Exception e) {
    log.error("Unhandled failure serving a request", e);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "internal-error",
        "Something failed",
        "The request could not be completed. The failure is in the server log.");
  }

  private static ResponseEntity<ApiError> problem(
      HttpStatus status, String type, String title, String detail) {
    return ResponseEntity.status(status)
        .body(new ApiError("urn:commander:" + type, title, status.value(), detail));
  }
}
