package com.lapczynski.commander.adk.plugin;

import com.lapczynski.commander.application.port.SafetyMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Micrometer implementation of the safety counters.
 *
 * <p>Metric names are fixed here and referenced by the Grafana dashboard in {@code
 * docs/dashboards/}. A rename breaks a dashboard silently — the panel simply shows no data — so the
 * names live in one place with the dashboard pointed at them.
 *
 * <p>Tag cardinality is bounded by construction: every tag value is an enumeration name or a fixed
 * refusal reason, never a resource id, an incident id, or anything else that grows with usage. An
 * unbounded tag turns a metrics backend into an outage.
 */
@Component
public class MicrometerSafetyMetrics implements SafetyMetrics {

  public static final String POLICY_DECISIONS = "commander.policy.decisions";
  public static final String APPROVAL_DECISIONS = "commander.approval.decisions";
  public static final String APPROVAL_WAIT = "commander.approval.wait";
  public static final String VERIFICATION_OUTCOMES = "commander.verification.outcomes";
  public static final String MODEL_CALLS_UNTRACKED = "commander.model.calls.untracked";

  private final MeterRegistry meters;

  public MicrometerSafetyMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void policyDecision(String decision) {
    Counter.builder(POLICY_DECISIONS)
        .description("Deterministic policy verdicts on proposed actions")
        .tag("decision", decision)
        .register(meters)
        .increment();
  }

  @Override
  public void approvalDecision(String outcome) {
    Counter.builder(APPROVAL_DECISIONS)
        .description("Approval outcomes, including refusals and why they were refused")
        .tag("outcome", outcome)
        .register(meters)
        .increment();
  }

  @Override
  public void approvalWait(Duration waited) {
    Timer.builder(APPROVAL_WAIT)
        .description("Time an approval request waited for a human decision")
        // Buckets so the dashboard's p95 panel has something to compute over. The range matters
        // here: approvals expire, so the interesting question is how close the distribution sits
        // to the expiry window, not what the mean is.
        .publishPercentileHistogram()
        .minimumExpectedValue(Duration.ofSeconds(10))
        .maximumExpectedValue(Duration.ofHours(2))
        .register(meters)
        .record(waited);
  }

  @Override
  public void verificationOutcome(String outcome) {
    Counter.builder(VERIFICATION_OUTCOMES)
        .description("Whether remediations actually worked")
        .tag("outcome", outcome)
        .register(meters)
        .increment();
  }

  @Override
  public void modelCallUntracked() {
    Counter.builder(MODEL_CALLS_UNTRACKED)
        .description("Model calls whose provider reported no token usage, so spend went unmeasured")
        .register(meters)
        .increment();
  }
}
