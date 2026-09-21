package com.lapczynski.commander.api.web;

import com.lapczynski.commander.api.config.DemoScenarioSource;
import com.lapczynski.commander.api.security.CurrentActor;
import com.lapczynski.commander.application.IncidentService;
import com.lapczynski.commander.application.port.AlertSignal;
import com.lapczynski.commander.application.report.IncidentReportService;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.Severity;
import com.lapczynski.commander.simulator.Scenario;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The operator console.
 *
 * <p>Server-rendered, and deliberately so. The thing this page exists to do is let a human read a
 * proposed action and decide it, and every fact on it — the action, the ARN, the risk, the
 * fingerprint the approval binds to — has to be the one the server will act on. A client-side
 * rendering would introduce a second copy of that state and, with it, the possibility of approving
 * what the screen said rather than what the server held.
 *
 * <p>HTMX refreshes the two queues on a timer. There is no SSE and no websocket: the investigation
 * is synchronous (see {@link IncidentController}), so the only thing that changes behind the
 * operator's back is another person deciding an approval, and a three-second poll of two small
 * fragments is the proportionate answer to that.
 *
 * <p><strong>This controller decides nothing.</strong> Every button posts to the same {@link
 * IncidentService} the API calls, under the same method security. A console that had its own path
 * into the domain would be a second place for the approval rules to be got wrong.
 */
@Controller
public class ConsoleController {

  private static final Logger log = LoggerFactory.getLogger(ConsoleController.class);

  private final IncidentService incidents;
  private final IncidentReportService reports;
  private final CurrentActor currentActor;

  /**
   * Present only under the {@code simulator} profile.
   *
   * <p>An {@link ObjectProvider} rather than a nullable bean, so that the same console class serves
   * both profiles. Under {@code aws} there is no scenario picker because there is nothing to pick:
   * the signals come from the account the instance is pointed at.
   */
  private final ObjectProvider<DemoScenarioSource> scenarios;

  public ConsoleController(
      IncidentService incidents,
      IncidentReportService reports,
      CurrentActor currentActor,
      ObjectProvider<DemoScenarioSource> scenarios) {
    this.incidents = incidents;
    this.reports = reports;
    this.currentActor = currentActor;
    this.scenarios = scenarios;
  }

  @GetMapping("/")
  public String root() {
    return "redirect:/console";
  }

  @GetMapping("/login")
  public String login() {
    return "login";
  }

  @GetMapping("/console")
  public String console(Model model) {
    Actor actor = currentActor.get();
    model.addAttribute("actor", actor);
    model.addAttribute("canApprove", actor.role().canApprove());
    model.addAttribute("scenarios", availableScenarios());
    model.addAttribute("currentScenario", currentScenario().orElse(null));
    queues(model);
    return "console";
  }

  /**
   * The two queues, as a fragment.
   *
   * <p>One endpoint for both because they are read together and refreshed together. Two polls at
   * the same interval would double the request count to show the same screen.
   */
  @GetMapping("/console/queues")
  public String queues(Model model) {
    List<Incident> open = incidents.openIncidents(25);
    List<ApprovalRequest> pending = incidents.pendingApprovals();

    model.addAttribute("incidents", open.stream().map(ApiModels.IncidentView::of).toList());
    model.addAttribute("approvals", pending.stream().map(ApiModels.ApprovalView::of).toList());
    model.addAttribute("canApprove", currentActor.get().role().canApprove());
    return "fragments/queues :: queues";
  }

  @GetMapping("/console/incidents/{id}")
  public String incident(@PathVariable String id, Model model) {
    IncidentId incidentId = IncidentId.of(id);
    Incident incident = require(incidentId);
    Actor actor = currentActor.get();

    model.addAttribute("actor", actor);
    model.addAttribute("canApprove", actor.role().canApprove());
    model.addAttribute("incident", ApiModels.IncidentView.of(incident));
    model.addAttribute(
        "approval",
        incidents.pendingApprovalFor(incidentId).map(ApiModels.ApprovalView::of).orElse(null));
    model.addAttribute("report", reports.latest(incidentId).map(r -> r.markdown()).orElse(null));
    return "incident";
  }

  /**
   * Raises an incident from the scenario the demo is serving.
   *
   * <p>The metric, observed value and threshold come from the console's own form rather than from
   * the scenario fixture, because that is the honest shape: in a real deployment they arrive on the
   * alert, and a console that silently read them out of the thing being investigated would be
   * letting the subject set its own pass mark.
   */
  @PostMapping("/console/incidents")
  @PreAuthorize("hasRole('INVESTIGATOR') or hasRole('APPROVER')")
  public String raise(
      @RequestParam String title,
      @RequestParam String serviceName,
      @RequestParam(required = false) String metricName,
      @RequestParam(required = false) Double observedValue,
      @RequestParam(required = false) Double recoveryThreshold,
      @RequestParam(required = false) Severity severity) {

    AlertSignal alert =
        new AlertSignal(
            title,
            serviceName,
            environmentOf(),
            severity == null ? Severity.SEV3 : severity,
            metricName,
            observedValue == null ? Double.NaN : observedValue,
            recoveryThreshold == null ? Double.NaN : recoveryThreshold,
            "Raised from the operator console.");

    Incident incident = incidents.open(alert, currentActor.get());
    return "redirect:/console/incidents/" + incident.id();
  }

  /**
   * Runs the investigation and comes back when it is over.
   *
   * <p>The request is held for the duration, which can be tens of seconds. That is a deliberate
   * consequence of the synchronous workflow rather than an oversight: the alternative is a job id
   * and a second place where an in-flight investigation's state is tracked, and the state that
   * matters is already durable in the incident row.
   */
  @PostMapping("/console/incidents/{id}/investigate")
  @PreAuthorize("hasRole('INVESTIGATOR') or hasRole('APPROVER')")
  public String investigate(
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

    log.info("Console investigation: incidentId={} by={}", id, currentActor.get().id());
    incidents.investigate(incidentId, alert);
    return "redirect:/console/incidents/" + id;
  }

  @PostMapping("/console/approvals/{id}/approve")
  @PreAuthorize("hasRole('APPROVER')")
  public String approve(@PathVariable String id, @RequestParam(required = false) String comment) {
    Incident incident = incidents.approve(ApprovalId.of(id), currentActor.get(), comment);
    return "redirect:/console/incidents/" + incident.id();
  }

  @PostMapping("/console/approvals/{id}/reject")
  @PreAuthorize("hasRole('APPROVER')")
  public String reject(@PathVariable String id, @RequestParam(required = false) String comment) {
    Incident incident = incidents.reject(ApprovalId.of(id), currentActor.get(), comment);
    return "redirect:/console/incidents/" + incident.id();
  }

  /**
   * Switches the incident the simulator is serving.
   *
   * <p>Restricted to an approver, not because changing a fixture is dangerous but because the demo
   * is a shared surface: someone reading an investigation should not have the ground move under it
   * because a viewer clicked something.
   */
  @PostMapping("/console/scenario")
  @PreAuthorize("hasRole('APPROVER')")
  public String scenario(@RequestParam String scenarioId) {
    scenarios.ifAvailable(source -> source.start(scenarioId));
    return "redirect:/console";
  }

  private List<Scenario> availableScenarios() {
    DemoScenarioSource source = scenarios.getIfAvailable();
    return source == null ? List.of() : source.available();
  }

  private Optional<Scenario> currentScenario() {
    return Optional.ofNullable(scenarios.getIfAvailable()).map(DemoScenarioSource::current);
  }

  /**
   * The environment a console-raised incident belongs to.
   *
   * <p>Taken from the running scenario where there is one, so that a demo incident carries the same
   * environment as the signals that will be read for it. Getting this wrong is not cosmetic: the
   * policy engine compares the target's environment against the configured one, and an incident
   * raised into the wrong environment would be refused for a reason that looks like a bug.
   */
  private String environmentOf() {
    return currentScenario().map(Scenario::environment).orElse("demo");
  }

  private Incident require(IncidentId id) {
    return incidents.find(id).orElseThrow(() -> new NoSuchElementException("no incident " + id));
  }
}
