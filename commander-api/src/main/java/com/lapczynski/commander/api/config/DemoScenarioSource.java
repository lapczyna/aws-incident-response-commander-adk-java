package com.lapczynski.commander.api.config;

import com.lapczynski.commander.application.signal.AlarmsPort;
import com.lapczynski.commander.application.signal.ChangeHistoryPort;
import com.lapczynski.commander.application.signal.DeploymentHistoryPort;
import com.lapczynski.commander.application.signal.EcsPort;
import com.lapczynski.commander.application.signal.LogsPort;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.application.signal.TimeWindow;
import com.lapczynski.commander.simulator.Scenario;
import com.lapczynski.commander.simulator.ScenarioLibrary;
import com.lapczynski.commander.simulator.ScenarioRun;
import com.lapczynski.commander.simulator.SimulatedSignalSource;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The running demo's view of the world.
 *
 * <p>{@link SimulatedSignalSource} is deliberately immutable and tied to one {@link ScenarioRun}:
 * its determinism comes from a fixed start instant, and a source whose clock moved underneath it
 * would return a window that slides while an investigation is reading it. That is right for a test
 * and wrong for a long-lived process, which needs to be able to start a different incident without
 * restarting.
 *
 * <p>So this holds the current run behind a reference and delegates every port to it. Starting a
 * scenario replaces the reference atomically; an investigation already in flight keeps the run it
 * began with, because it is holding the delegate rather than looking it up again.
 *
 * <p>Only exists under the {@code simulator} profile. Under {@code aws} the same six interfaces are
 * satisfied by adapters that read the real thing, which is the property that lets the entire
 * workflow be demonstrated with no AWS account.
 */
public class DemoScenarioSource
    implements MetricsPort,
        LogsPort,
        AlarmsPort,
        EcsPort,
        ChangeHistoryPort,
        DeploymentHistoryPort {

  private static final Logger log = LoggerFactory.getLogger(DemoScenarioSource.class);

  private final ScenarioLibrary library;
  private final Clock clock;
  private final AtomicReference<SimulatedSignalSource> active = new AtomicReference<>();
  private final AtomicReference<Scenario> scenario = new AtomicReference<>();

  public DemoScenarioSource(ScenarioLibrary library, Clock clock, String initialScenarioId) {
    this.library = library;
    this.clock = clock;
    start(initialScenarioId);
  }

  /**
   * Starts a scenario, replacing whatever was running.
   *
   * @return the scenario now serving signals
   * @throws IllegalArgumentException if no such fixture exists. Failing loudly beats serving the
   *     previous scenario under a new name, which would produce an investigation that looks
   *     coherent and is about the wrong incident.
   */
  public Scenario start(String scenarioId) {
    Scenario selected = library.require(scenarioId);
    scenario.set(selected);
    active.set(new SimulatedSignalSource(new ScenarioRun(selected, clock.instant())));

    log.info("Demo scenario started: id={} service={}", selected.id(), selected.serviceName());
    return selected;
  }

  /** The scenario currently serving signals. */
  public Scenario current() {
    return scenario.get();
  }

  public List<Scenario> available() {
    return library.all();
  }

  private SimulatedSignalSource delegate() {
    return active.get();
  }

  // ------------------------------------------------------------------ delegation

  @Override
  public MetricSeries query(MetricQuery query) {
    return delegate().query(query);
  }

  @Override
  public LogQueryResult query(LogQuery query) {
    return delegate().query(query);
  }

  @Override
  public List<AlarmState> activeAlarms(String serviceName) {
    return delegate().activeAlarms(serviceName);
  }

  @Override
  public ServiceState serviceState(String serviceName) {
    return delegate().serviceState(serviceName);
  }

  @Override
  public List<Deployment> deployments(String serviceName, int limit) {
    return delegate().deployments(serviceName, limit);
  }

  @Override
  public List<ChangeEvent> recentChanges(String serviceName, TimeWindow window, int limit) {
    return delegate().recentChanges(serviceName, window, limit);
  }

  @Override
  public List<DeployedVersion> history(String serviceName, int limit) {
    return delegate().history(serviceName, limit);
  }
}
