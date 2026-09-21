package com.lapczynski.commander.api.web;

import com.lapczynski.commander.api.config.DemoScenarioSource;
import com.lapczynski.commander.simulator.Scenario;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which simulated incident the demo is serving.
 *
 * <p>Exists so a demonstration can move between scenarios without a restart, which matters because
 * the interesting ones are the failures: the scenario where verification proves the remediation did
 * not work is the one worth showing, and it is the fourth one down the list.
 *
 * <p><strong>Registered only under the {@code simulator} profile.</strong> Not guarded by a runtime
 * check but by the absence of its dependency: {@link DemoScenarioSource} is a simulator-profile
 * bean, so under {@code aws} this controller is not constructed and the paths do not exist. An
 * endpoint that replaced the world's signals would be a strange thing to leave reachable, however
 * carefully it checked a flag.
 */
@RestController
@Profile("simulator")
@RequestMapping("/api/demo/scenarios")
@Tag(name = "Demo", description = "The simulated incident being served")
public class DemoController {

  private final DemoScenarioSource scenarios;

  public DemoController(DemoScenarioSource scenarios) {
    this.scenarios = scenarios;
  }

  /** What an investigation of this scenario should conclude, alongside what it is. */
  record ScenarioView(
      String id, String title, String description, String service, String expectedOutcome) {

    static ScenarioView of(Scenario scenario) {
      return new ScenarioView(
          scenario.id(),
          scenario.title(),
          scenario.description(),
          scenario.serviceName(),
          scenario.expectedOutcome().name());
    }
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "The scenarios this build ships with")
  public List<ScenarioView> available() {
    return scenarios.available().stream().map(ScenarioView::of).toList();
  }

  @GetMapping(value = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "The scenario currently serving signals")
  public ScenarioView current() {
    return ScenarioView.of(scenarios.current());
  }

  /**
   * Starts a scenario, replacing whatever was running.
   *
   * <p>Approver-only. Not because switching a fixture can break anything, but because it changes
   * what every other reader on the demo is looking at partway through their investigation.
   */
  @PostMapping(value = "/{id}/start", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('APPROVER')")
  @Operation(summary = "Start a scenario")
  public ScenarioView start(@PathVariable String id) {
    return ScenarioView.of(scenarios.start(id));
  }
}
