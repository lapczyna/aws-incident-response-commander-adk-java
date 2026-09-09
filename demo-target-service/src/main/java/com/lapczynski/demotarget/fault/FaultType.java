package com.lapczynski.demotarget.fault;

import java.time.Duration;

/**
 * The closed set of faults this service can be asked to simulate.
 *
 * <p>Each constant carries its own hard ceilings. Those ceilings are not configuration: they are
 * compiled in, so no request body, property file or environment variable can raise them. A demo
 * that can be talked into exhausting its own host is not a demo, it is an outage.
 *
 * <p>Deliberately absent, and never to be added: unbounded memory exhaustion, fork bombs, and
 * uninterruptible loops. Every fault here is bounded in magnitude and expires on its own.
 */
public enum FaultType {

  /** Return 5xx for a fraction of requests. */
  ERROR_RATE(Duration.ofMinutes(30)),

  /** Add fixed latency to every request. */
  LATENCY(Duration.ofMinutes(30)),

  /** Make calls to a named downstream dependency time out. */
  DOWNSTREAM_TIMEOUT(Duration.ofMinutes(30)),

  /** Hold database connections open to starve the pool. */
  DB_POOL_PRESSURE(Duration.ofMinutes(10)),

  /** Fail requests to one specific path only. */
  ENDPOINT_FAILURE(Duration.ofMinutes(30)),

  /** Burn CPU on a bounded number of threads, with a duty cycle. */
  CPU_PRESSURE(Duration.ofMinutes(5)),

  /** Report unhealthy or degraded readiness without otherwise changing behaviour. */
  UNHEALTHY_READINESS(Duration.ofMinutes(30)),

  /** Report a different deployed version, simulating a bad rollout. */
  BAD_VERSION(Duration.ofMinutes(30));

  /** Longest any fault of this type may remain active, regardless of what was requested. */
  private final Duration maxDuration;

  FaultType(Duration maxDuration) {
    this.maxDuration = maxDuration;
  }

  public Duration maxDuration() {
    return maxDuration;
  }

  /** Clamps a requested duration to this type's ceiling, and rejects non-positive values. */
  public Duration clampDuration(Duration requested) {
    if (requested == null || requested.isZero() || requested.isNegative()) {
      throw new IllegalArgumentException("fault duration must be positive");
    }
    return requested.compareTo(maxDuration) > 0 ? maxDuration : requested;
  }

  // ----- magnitude ceilings, all compiled in -----

  /** Longest latency that may be injected per request. */
  public static final Duration MAX_LATENCY = Duration.ofSeconds(5);

  /**
   * Most CPU-burning threads permitted.
   *
   * <p>Resolved against the actual host rather than fixed, and always leaves at least one core
   * free, so the service stays able to answer the request that turns the fault off.
   */
  public static int maxCpuThreads() {
    return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
  }

  /** Fraction of each CPU-pressure cycle spent burning, leaving the rest as sleep. */
  public static final double CPU_DUTY_CYCLE = 0.7;

  /** Length of one CPU-pressure duty cycle. */
  public static final Duration CPU_CYCLE = Duration.ofMillis(100);
}
