package com.lapczynski.demotarget.fault;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for which faults are active.
 *
 * <p>Three independent safety properties, each of which would be sufficient on its own and none of
 * which is relied on alone:
 *
 * <ol>
 *   <li><strong>Off by default.</strong> {@code demo.faults.enabled} must be explicitly true.
 *       Injection is refused otherwise, so an accidentally deployed image is inert.
 *   <li><strong>Expiry checked on read.</strong> {@link #active} filters expired faults itself
 *       rather than trusting the sweeper. If the scheduler is wedged, misconfigured, or the clock
 *       jumps, faults still stop applying at their deadline.
 *   <li><strong>A sweeper as well.</strong> It only reclaims resources and logs; correctness does
 *       not depend on it running.
 * </ol>
 *
 * <p>The read path is deliberately the strict one. A sweeper-only design fails open: miss one tick
 * and a fault outlives its deadline silently.
 */
@Component
public class FaultRegistry {

  private static final Logger log = LoggerFactory.getLogger(FaultRegistry.class);

  private final Map<FaultType, ActiveFault> faults = new ConcurrentHashMap<>();
  private final Clock clock;
  private final boolean enabled;

  public FaultRegistry(Clock clock, @Value("${demo.faults.enabled:false}") boolean enabled) {
    this.clock = clock;
    this.enabled = enabled;
    if (enabled) {
      log.warn(
          "Fault injection is ENABLED. This service will deliberately misbehave on request. "
              + "Never enable this outside a demo environment.");
    }
  }

  /** Whether this instance will accept fault injection at all. */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Activates a fault, replacing any existing one of the same type.
   *
   * @throws FaultInjectionDisabledException if injection is not enabled on this instance
   * @throws IllegalArgumentException if the requested magnitude is outside the type's ceiling
   */
  public ActiveFault inject(
      FaultType type, Map<String, String> parameters, Duration duration, String activatedBy) {
    if (!enabled) {
      throw new FaultInjectionDisabledException();
    }

    Map<String, String> clamped = FaultParameters.clamp(type, parameters);
    ActiveFault fault = ActiveFault.starting(type, clamped, duration, activatedBy, clock.instant());

    faults.put(type, fault);
    log.warn(
        "Fault injected: type={} parameters={} expiresAt={} activatedBy={}",
        type,
        clamped,
        fault.expiresAt(),
        activatedBy);
    return fault;
  }

  /**
   * The active fault of a type, if one is active and unexpired.
   *
   * <p>Expiry is evaluated here, on every read, so a fault stops applying at its deadline whether
   * or not the sweeper has run.
   */
  public Optional<ActiveFault> active(FaultType type) {
    ActiveFault fault = faults.get(type);
    if (fault == null) {
      return Optional.empty();
    }
    if (fault.hasExpired(clock.instant())) {
      return Optional.empty();
    }
    return Optional.of(fault);
  }

  public boolean isActive(FaultType type) {
    return active(type).isPresent();
  }

  /** Every currently active, unexpired fault. */
  public Map<FaultType, ActiveFault> activeFaults() {
    Instant now = clock.instant();
    Map<FaultType, ActiveFault> snapshot = new EnumMap<>(FaultType.class);
    faults.forEach(
        (type, fault) -> {
          if (!fault.hasExpired(now)) {
            snapshot.put(type, fault);
          }
        });
    return snapshot;
  }

  /** Deactivates one fault. Returns whether anything was active. */
  public boolean clear(FaultType type) {
    ActiveFault removed = faults.remove(type);
    if (removed != null) {
      log.info("Fault cleared: type={}", type);
      return true;
    }
    return false;
  }

  /**
   * Deactivates everything.
   *
   * <p>Always available, even when injection is disabled: turning faults off must never be
   * something the configuration can block.
   */
  public int clearAll() {
    int count = faults.size();
    faults.clear();
    if (count > 0) {
      log.info("All faults cleared: count={}", count);
    }
    return count;
  }

  /**
   * Reclaims expired entries.
   *
   * <p>Housekeeping only. {@link #active} has already stopped honouring these, so a missed run
   * delays cleanup rather than extending a fault.
   */
  @Scheduled(fixedDelay = 5_000)
  public void sweepExpired() {
    Instant now = clock.instant();
    faults.forEach(
        (type, fault) -> {
          if (fault.hasExpired(now) && faults.remove(type, fault)) {
            log.info("Fault expired: type={} activatedAt={}", type, fault.activatedAt());
          }
        });
  }

  /** Thrown when injection is attempted on an instance where it is not enabled. */
  public static class FaultInjectionDisabledException extends RuntimeException {
    public FaultInjectionDisabledException() {
      super(
          "Fault injection is disabled on this instance. Set demo.faults.enabled=true to allow it, "
              + "and never do so outside a demo environment.");
    }
  }
}
