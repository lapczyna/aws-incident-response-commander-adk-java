package com.lapczynski.commander.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.lapczynski.commander.adk.approval.RemediationTool;
import com.lapczynski.commander.application.port.IdempotencyStore;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.policy.PolicyConfiguration;
import com.lapczynski.commander.domain.policy.PolicyEngine;
import com.lapczynski.commander.domain.remediation.ActionType;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the remediation tool refuses, and what it has already written when it refuses.
 *
 * <p>The second half is the point. A guard that reaches the right verdict after writing a row is
 * not a guard, and the specific write here — claiming an idempotency key — carries a foreign key to
 * the incident. Claiming for an incident that does not exist produced a constraint violation thrown
 * out of the tool, which the caller reported as "the executor returned an unrecognised status:
 * error": a refusal presented as a malfunction.
 *
 * <p>These tests use an {@link IdempotencyStore} that fails if it is touched at all, so the
 * assertion is on the order of operations rather than on the message that came back.
 */
@DisplayName("The remediation tool's guards")
class RemediationToolGuardTest {

  private static final String ALLOWED_ARN =
      "arn:aws:ecs:eu-west-1:123456789012:service/demo/checkout";

  private static final Map<String, String> VALID_TAGS =
      Map.of("Project", "aws-incident-response-commander", "Environment", "demo");

  /**
   * A deployment that permits exactly this action against exactly this resource.
   *
   * <p>Everything else has to be permissive, or these tests would pass for the wrong reason: a
   * refusal is uninteresting if policy would have refused anyway.
   */
  private static PolicyEngine permissiveEngine() {
    return new PolicyEngine(
        new PolicyConfiguration(
            true,
            true,
            "123456789012",
            "eu-west-1",
            "demo",
            Set.of(ActionType.ROLLBACK_DEPLOYMENT),
            Set.of(ALLOWED_ARN),
            new PolicyConfiguration.TagRequirement("Project", "aws-incident-response-commander"),
            new Confidence(0.7),
            3));
  }

  @Test
  @DisplayName("refuses an incident that does not exist, having written nothing")
  void refusesAnUnknownIncidentBeforeClaiming() {
    RefusingStore store = new RefusingStore();
    RemediationTool tool =
        new RemediationTool(
            permissiveEngine(),
            incidentId -> Optional.empty(),
            store,
            VALID_TAGS,
            action -> "should never be reached");

    Map<String, Object> result = execute(tool);

    assertThat(result.get("status")).isEqualTo("refused");
    assertThat(result.get("reason")).isEqualTo("UNKNOWN_INCIDENT");
    assertThat(store.touched())
        .as("nothing may be written before the request is known to refer to a real incident")
        .isZero();
  }

  /**
   * The status rule, which used to be unfalsifiable here.
   *
   * <p>The tool passed {@code IncidentStatus.REMEDIATING} to the policy engine as a constant, so
   * {@code INCIDENT_STATUS_FORBIDS_EXECUTION} could never fire at execution time — the one place
   * the engine's own comment says it has to. A resolved incident is the clearest case: whatever was
   * approved, it was not an action against an incident that is already over.
   */
  @Test
  @DisplayName("refuses an incident whose status forbids execution")
  void refusesWhenTheIncidentIsNotOneActionsMayRunFrom() {
    RefusingStore store = new RefusingStore();
    RemediationTool tool =
        new RemediationTool(
            permissiveEngine(),
            incidentId -> Optional.of(IncidentStatus.RESOLVED),
            store,
            VALID_TAGS,
            action -> "should never be reached");

    Map<String, Object> result = execute(tool);

    assertThat(result.get("status")).isEqualTo("refused");
    assertThat(result.get("reason")).isEqualTo("POLICY_DENIED_AT_EXECUTION");
    assertThat(String.valueOf(result.get("detail")))
        .as(
            "the refusal has to name the rule, or an operator cannot tell it from an allowlist miss")
        .contains("RESOLVED");
    assertThat(store.touched()).isZero();
  }

  /**
   * The guard does not block legitimate work.
   *
   * <p>Worth asserting explicitly: a check added to stop a crash is easy to write in a way that
   * stops everything, and both refusal tests above would still pass if it did.
   */
  @Test
  @DisplayName("executes when the incident is real and being remediated")
  void executesForAnIncidentUnderRemediation() {
    RecordingStore store = new RecordingStore();
    RemediationTool tool =
        new RemediationTool(
            permissiveEngine(),
            incidentId -> Optional.of(IncidentStatus.REMEDIATING),
            store,
            VALID_TAGS,
            action -> "rolled back");

    Map<String, Object> result = execute(tool);

    assertThat(result.get("status")).isEqualTo("executed");
    assertThat(result.get("dryRun")).isEqualTo(true);
    assertThat(store.claims()).isOne();
  }

  private static Map<String, Object> execute(RemediationTool tool) {
    return tool.executeRemediation(
        IncidentId.newId().toString(),
        0,
        "ROLLBACK_DEPLOYMENT",
        ALLOWED_ARN,
        "123456789012",
        "eu-west-1",
        "demo",
        0.82,
        "Roll the checkout service back to revision 41.");
  }

  /** Fails the test if anything writes to it. */
  private static final class RefusingStore implements IdempotencyStore {
    private final AtomicInteger touched = new AtomicInteger();

    int touched() {
      return touched.get();
    }

    @Override
    public boolean claim(ActionFingerprint fingerprint, IncidentId incidentId) {
      touched.incrementAndGet();
      return true;
    }

    @Override
    public void complete(ActionFingerprint fingerprint, Outcome outcome, String result) {
      touched.incrementAndGet();
    }

    @Override
    public Optional<Record> find(ActionFingerprint fingerprint) {
      return Optional.empty();
    }
  }

  /** Claims once, so the happy path can assert that it did. */
  private static final class RecordingStore implements IdempotencyStore {
    private final AtomicInteger claims = new AtomicInteger();

    int claims() {
      return claims.get();
    }

    @Override
    public boolean claim(ActionFingerprint fingerprint, IncidentId incidentId) {
      return claims.incrementAndGet() == 1;
    }

    @Override
    public void complete(ActionFingerprint fingerprint, Outcome outcome, String result) {
      // Nothing to record.
    }

    @Override
    public Optional<Record> find(ActionFingerprint fingerprint) {
      return Optional.empty();
    }
  }
}
