package com.lapczynski.commander.api.web;

import com.lapczynski.commander.application.port.AlertSignal;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * What the API accepts and returns.
 *
 * <p>Separate types from the domain records, and not because layering demands it. The domain
 * carries things that must not be serialised onto the wire and things a client must not be able to
 * set: an incident's {@code version} is an optimistic-locking token and part of the approval
 * fingerprint, and an endpoint that accepted one would let a caller choose which version their
 * approval binds to.
 *
 * <p>The responses are deliberately flat and complete. An approver deciding whether to authorise an
 * action should not have to make a second call to find out what the action is.
 */
final class ApiModels {

  private ApiModels() {}

  // ------------------------------------------------------------------ requests

  @Schema(description = "An alert to open an incident from.")
  record RaiseIncident(
      @NotBlank @Size(max = 200) @Schema(example = "Checkout p99 latency tripled") String title,
      @NotBlank @Size(max = 100) @Schema(example = "checkout") String serviceName,
      @Size(max = 50) @Schema(example = "demo") String environment,
      @Schema(example = "SEV2") Severity severity,
      @Size(max = 100)
          @Schema(
              description =
                  "The metric recovery will be measured on. Omit it and the incident can still be "
                      + "investigated, but recovery verifies as INDETERMINATE.",
              example = "TargetResponseTimeP99")
          String metricName,
      @Schema(description = "What the metric read when the alert fired.", example = "1450.0")
          Double observedValue,
      @Schema(
              description = "The value it must return below for the symptom to be gone.",
              example = "500.0")
          Double recoveryThreshold,
      @Size(max = 2000) String description) {

    AlertSignal toSignal(String defaultEnvironment) {
      return new AlertSignal(
          title,
          serviceName,
          environment == null || environment.isBlank() ? defaultEnvironment : environment,
          severity == null ? Severity.SEV3 : severity,
          metricName,
          observedValue == null ? Double.NaN : observedValue,
          recoveryThreshold == null ? Double.NaN : recoveryThreshold,
          description);
    }
  }

  @Schema(description = "A human's decision on a proposed remediation.")
  record Decision(
      @Size(max = 1000)
          @Schema(
              description =
                  "Why. Recorded in the audit trail against the approver, and worth writing: it is "
                      + "what the next reader of this incident has to go on.")
          String comment) {}

  // ------------------------------------------------------------------ responses

  @Schema(description = "An incident.")
  record IncidentView(
      String id,
      String title,
      String service,
      String environment,
      String severity,
      String status,
      long version,
      Instant receivedAt,
      Instant updatedAt,
      String summary,
      String closingNote,
      boolean awaitingHuman,
      boolean terminal) {

    static IncidentView of(Incident incident) {
      return new IncidentView(
          incident.id().toString(),
          incident.title(),
          incident.affectedService().name(),
          incident.affectedService().environment(),
          incident.severity().name(),
          incident.status().name(),
          incident.version(),
          incident.receivedAt(),
          incident.updatedAt(),
          incident.summary().orElse(null),
          incident.closingNote().orElse(null),
          incident.status().isAwaitingHuman(),
          incident.status().isTerminal());
    }
  }

  @Schema(description = "A remediation waiting for a human decision.")
  record ApprovalView(
      String id,
      String incidentId,
      long incidentVersion,
      String action,
      String targetArn,
      String targetEnvironment,
      String risk,
      String status,
      String rationale,
      String expectedImpact,
      List<String> supportingEvidence,
      Instant requestedAt,
      Instant expiresAt,
      @Schema(
              description =
                  "The first eight characters of the fingerprint this approval binds to. Shown "
                      + "rather than the whole hash because it is for recognising a decision in a "
                      + "log, not for verifying one.")
          String fingerprint) {

    static ApprovalView of(ApprovalRequest request) {
      return new ApprovalView(
          request.id().toString(),
          request.incidentId().toString(),
          request.incidentVersion(),
          request.action().type().name(),
          request.action().target().arn(),
          request.action().target().environment(),
          request.risk().name(),
          request.status().name(),
          request.rationale(),
          request.expectedImpact(),
          request.supportingEvidence().stream().map(Object::toString).toList(),
          request.requestedAt(),
          request.expiresAt(),
          request.fingerprint().abbreviated());
    }
  }

  @Schema(description = "A problem, in the shape RFC 9457 describes.")
  record ApiError(String type, String title, int status, String detail) {}
}
