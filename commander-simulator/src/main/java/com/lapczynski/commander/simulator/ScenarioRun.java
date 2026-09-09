package com.lapczynski.commander.simulator;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One running instance of a scenario: the fixture, the instant it started, and the call counters
 * that drive scripted failures.
 *
 * <p>The start instant is what turns relative fixture offsets into concrete timestamps. It is
 * captured once when the scenario is started and never moves, so two queries a second apart see a
 * consistent world rather than a window that slides underneath them.
 *
 * <p>The same fixture can be started repeatedly; each run gets its own counters, so a scripted
 * "fail after the second call" behaves identically every time rather than depending on how many
 * times the process has been used.
 */
public final class ScenarioRun {

  private final Scenario scenario;
  private final Instant startedAt;
  private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();

  public ScenarioRun(Scenario scenario, Instant startedAt) {
    this.scenario = Objects.requireNonNull(scenario, "scenario must not be null");
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
  }

  public Scenario scenario() {
    return scenario;
  }

  public Instant startedAt() {
    return startedAt;
  }

  /** Converts a fixture offset in minutes into an absolute instant. Negative is in the past. */
  public Instant at(long offsetMinutes) {
    return startedAt.plusSeconds(offsetMinutes * 60);
  }

  /**
   * Records a call against a named source and returns the 1-based call number.
   *
   * <p>Used to decide whether a scripted failure should fire yet.
   */
  public int recordCall(String sourceName) {
    return callCounts.computeIfAbsent(sourceName, key -> new AtomicInteger()).incrementAndGet();
  }

  /** How many times a source has been queried in this run. */
  public int callCount(String sourceName) {
    AtomicInteger counter = callCounts.get(sourceName);
    return counter == null ? 0 : counter.get();
  }

  /**
   * Whether the scenario scripts a failure for {@code sourceName} at the current call number.
   *
   * <p>Call this <em>after</em> {@link #recordCall}, so the count includes the call being made.
   */
  public boolean shouldFail(String sourceName) {
    Scenario.FailureFixture failure = scenario.failures().get(sourceName);
    return failure != null && callCount(sourceName) > failure.failFromCall();
  }

  public Scenario.FailureFixture failureFor(String sourceName) {
    return scenario.failures().get(sourceName);
  }
}
