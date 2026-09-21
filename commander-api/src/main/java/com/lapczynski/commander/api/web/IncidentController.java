package com.lapczynski.commander.api.web;

import com.lapczynski.commander.api.config.CommanderProperties;
import com.lapczynski.commander.api.security.CurrentActor;
import com.lapczynski.commander.api.web.ApiModels.IncidentView;
import com.lapczynski.commander.api.web.ApiModels.RaiseIncident;
import com.lapczynski.commander.application.IncidentService;
import com.lapczynski.commander.application.port.AlertSignal;
import com.lapczynski.commander.application.report.IncidentReportService;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incidents over HTTP.
 *
 * <p>Raising and investigating are two calls, not one. An alert that arrives is worth recording
 * whether or not an agent is available to look at it, and collapsing them would mean an alert is
 * lost precisely when the model provider is down — which is exactly when alerts arrive in numbers.
 *
 * <p>The investigation is synchronous, which is honest rather than elegant: it takes as long as it
 * takes, and the caller's connection is the thing keeping track. The alternative — a job id and a
 * polling endpoint — would add a queue this system does not have, and the console watches progress
 * through the incident's status either way.
 */
@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incidents", description = "Raising, investigating and reading incidents")
public class IncidentController {

  private static final Logger log = LoggerFactory.getLogger(IncidentController.class);

  private final IncidentService incidents;
  private final IncidentReportService reports;
  private final CurrentActor currentActor;
  private final String defaultEnvironment;

  public IncidentController(
      IncidentService incidents,
      IncidentReportService reports,
      CurrentActor currentActor,
      CommanderProperties properties) {
    this.incidents = incidents;
    this.reports = reports;
    this.currentActor = currentActor;
    this.defaultEnvironment = properties.policy().environment();
  }

  @PostMapping
  @PreAuthorize("hasRole('INVESTIGATOR') or hasRole('APPROVER')")
  @Operation(
      summary = "Raise an incident from an alert",
      description =
          "Records the alert and nothing else. The metric, observed value and recovery threshold "
              + "are captured here because recovery is later judged by comparing against them; "
              + "deriving them after the investigation would let the thing being judged choose its "
              + "own criteria.")
  public ResponseEntity<IncidentView> raise(@Valid @RequestBody RaiseIncident request) {
    AlertSignal alert = request.toSignal(defaultEnvironment);
    Incident incident = incidents.open(alert, currentActor.get());

    return ResponseEntity.created(URI.create("/api/incidents/" + incident.id()))
        .body(IncidentView.of(incident));
  }

  @PostMapping("/{id}/investigate")
  @PreAuthorize("hasRole('INVESTIGATOR') or hasRole('APPROVER')")
  @Operation(
      summary = "Run the investigation",
      description =
          "Gathers evidence, forms a hypothesis under critique, proposes a remediation and submits "
              + "it to the policy gate. Returns with the incident either awaiting a human, resolved "
              + "with nothing to do, or failed — never mid-flight.")
  public IncidentView investigate(
      @PathVariable String id,
      @RequestParam(required = false) String metricName,
      @RequestParam(required = false) Double observedValue,
      @RequestParam(required = false) Double recoveryThreshold) {

    IncidentId incidentId = IncidentId.of(id);
    Incident incident = require(incidentId);

    AlertSignal alert =
        new AlertSignal(
            incident.title(),
            incident.affectedService().name(),
            incident.affectedService().environment(),
            incident.severity(),
            metricName,
            observedValue == null ? Double.NaN : observedValue,
            recoveryThreshold == null ? Double.NaN : recoveryThreshold,
            "");

    log.info("Investigation requested: incidentId={} by={}", id, currentActor.get().id());
    return IncidentView.of(incidents.investigate(incidentId, alert));
  }

  @GetMapping
  @Operation(summary = "List incidents that are still in flight")
  public List<IncidentView> open(@RequestParam(defaultValue = "25") int limit) {
    return incidents.openIncidents(Math.clamp(limit, 1, 100)).stream()
        .map(IncidentView::of)
        .toList();
  }

  @GetMapping("/{id}")
  @Operation(summary = "Read one incident")
  public IncidentView one(@PathVariable String id) {
    return IncidentView.of(require(IncidentId.of(id)));
  }

  /**
   * The postmortem, as Markdown.
   *
   * <p>Markdown rather than JSON because the report is a document: its timeline, evidence table and
   * verification block are assembled by code and meant to be read, not parsed. A JSON rendering
   * would invite a client to reassemble it and disagree with the renderer about what it says.
   */
  @GetMapping(value = "/{id}/report", produces = MediaType.TEXT_MARKDOWN_VALUE)
  @Operation(summary = "The incident report")
  public ResponseEntity<String> report(@PathVariable String id) {
    IncidentId incidentId = IncidentId.of(id);
    return reports
        .latest(incidentId)
        .map(report -> ResponseEntity.ok(report.markdown()))
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    "no report for incident %s; one is written when the incident closes"
                        .formatted(id)));
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("hasRole('APPROVER')")
  @Operation(
      summary = "Cancel an incident",
      description =
          "Only before an action starts. Once remediation is under way the state machine does not "
              + "offer cancellation, because pretending an action in flight can be called off "
              + "would be a lie about the world.")
  public IncidentView cancel(
      @PathVariable String id, @RequestBody(required = false) ApiModels.Decision decision) {
    Actor actor = currentActor.get();
    String reason = decision == null ? null : decision.comment();
    return IncidentView.of(incidents.cancel(IncidentId.of(id), actor, reason));
  }

  private Incident require(IncidentId id) {
    return incidents.find(id).orElseThrow(() -> new NoSuchElementException("no incident " + id));
  }
}
