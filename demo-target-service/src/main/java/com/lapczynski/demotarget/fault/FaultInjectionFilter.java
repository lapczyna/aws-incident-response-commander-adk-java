package com.lapczynski.demotarget.fault;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies request-path faults: latency, error rate and per-endpoint failure.
 *
 * <p>The admin and actuator paths are exempt. Injecting latency or errors into the endpoint that
 * turns faults off would make a fault self-perpetuating, and injecting them into the health check
 * would make the fault indistinguishable from a genuinely broken service — both of which would turn
 * a demo into an incident nobody can end.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FaultInjectionFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(FaultInjectionFilter.class);

  /** Paths that must always work, so a fault can always be observed and switched off. */
  private static final String[] EXEMPT_PREFIXES = {"/admin/faults", "/actuator"};

  private final FaultRegistry registry;

  public FaultInjectionFilter(FaultRegistry registry) {
    this.registry = registry;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    for (String prefix : EXEMPT_PREFIXES) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    if (applyEndpointFailure(request, response)) {
      return;
    }
    if (applyErrorRate(response)) {
      return;
    }
    applyLatency();

    chain.doFilter(request, response);
  }

  /** Fails one specific path, leaving the rest of the service healthy. */
  private boolean applyEndpointFailure(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var fault = registry.active(FaultType.ENDPOINT_FAILURE);
    if (fault.isEmpty()) {
      return false;
    }
    String target = fault.get().stringParam("path", "");
    if (target.isEmpty() || !request.getRequestURI().startsWith(target)) {
      return false;
    }
    int status = fault.get().intParam("status", 500);
    response.setStatus(status);
    response.getWriter().write("{\"error\":\"injected endpoint failure\"}");
    response.setContentType("application/json");
    return true;
  }

  /** Fails a fraction of requests. */
  private boolean applyErrorRate(HttpServletResponse response) throws IOException {
    var fault = registry.active(FaultType.ERROR_RATE);
    if (fault.isEmpty()) {
      return false;
    }
    double rate = fault.get().doubleParam("rate", 0.0);
    if (ThreadLocalRandom.current().nextDouble() >= rate) {
      return false;
    }
    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"injected failure\"}");
    return true;
  }

  /**
   * Adds latency.
   *
   * <p>The delay is bounded by {@link FaultType#MAX_LATENCY} at injection time, and interruption is
   * honoured here so a shutdown is not held up by a sleeping request thread.
   */
  private void applyLatency() {
    var fault = registry.active(FaultType.LATENCY);
    if (fault.isEmpty()) {
      return;
    }
    long millis = Math.min(fault.get().intParam("millis", 0), FaultType.MAX_LATENCY.toMillis());
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Latency injection interrupted during shutdown");
    }
  }
}
