package com.lapczynski.demotarget.api;

import com.lapczynski.demotarget.fault.FaultRegistry;
import com.lapczynski.demotarget.fault.FaultType;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports readiness, honouring the {@code UNHEALTHY_READINESS} fault.
 *
 * <p>Health is reported through the standard actuator mechanism rather than a bespoke endpoint, so
 * an investigation sees a service failing its real health check — which is what would happen in
 * production — rather than a special demo signal that exists nowhere else.
 */
@Component
public class DemoHealthIndicator implements HealthIndicator {

  private final FaultRegistry registry;

  public DemoHealthIndicator(FaultRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Health health() {
    var fault = registry.active(FaultType.UNHEALTHY_READINESS);
    if (fault.isEmpty()) {
      return Health.up().withDetail("checks", "all dependencies reachable").build();
    }

    String state = fault.get().stringParam("state", "DOWN");
    Health.Builder builder =
        "DEGRADED".equalsIgnoreCase(state) ? Health.status("DEGRADED") : Health.down();

    return builder
        .withDetail("reason", "injected readiness fault")
        .withDetail("expiresAt", fault.get().expiresAt().toString())
        .build();
  }
}
