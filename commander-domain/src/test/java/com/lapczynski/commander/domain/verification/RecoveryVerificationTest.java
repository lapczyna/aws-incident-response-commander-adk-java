package com.lapczynski.commander.domain.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Recovery verification")
class RecoveryVerificationTest {

  @Nested
  @DisplayName("judging an outcome")
  class Judging {

    @Test
    @DisplayName("a metric back under the threshold is recovered")
    void recovered() {
      assertThat(RecoveryVerification.judge(1.15, 0.19, 0.4))
          .isEqualTo(RecoveryVerification.Outcome.RECOVERED);
    }

    @Test
    @DisplayName("sitting exactly on the threshold counts as recovered")
    void exactlyOnThreshold() {
      // The threshold is the value at which the alarm stops firing, so equality is the recovered
      // side of it. Choosing the other convention would leave an incident open on the exact value
      // that an operator was told is acceptable.
      assertThat(RecoveryVerification.judge(1.15, 0.4, 0.4))
          .isEqualTo(RecoveryVerification.Outcome.RECOVERED);
    }

    @Test
    @DisplayName("a metric that did not move is not recovered")
    void notRecovered() {
      assertThat(RecoveryVerification.judge(1.15, 1.14, 0.4))
          .isEqualTo(RecoveryVerification.Outcome.NOT_RECOVERED);
    }

    @Test
    @DisplayName("a metric that halved from a catastrophic level is still not recovered")
    void halvingFromCatastrophicIsNotRecovery() {
      // 100x the threshold down to 50x is a 50% improvement and a continuing outage. Judging
      // improvement as a ratio of the raw values would call this a success; judging it against
      // the distance back to the threshold does not.
      //
      // excessBefore = 39.6, excessAfter = 19.6, and 19.6 is not below half of 39.6 by enough to
      // matter — it is 49.5%, so this lands in PARTIALLY_RECOVERED rather than success. What it
      // must never be is RECOVERED.
      RecoveryVerification.Outcome outcome = RecoveryVerification.judge(40.0, 20.0, 0.4);

      assertThat(outcome).isNotEqualTo(RecoveryVerification.Outcome.RECOVERED);
      assertThat(outcome).isEqualTo(RecoveryVerification.Outcome.PARTIALLY_RECOVERED);
    }

    @Test
    @DisplayName("a large improvement that still breaches the threshold is partial, not success")
    void partialRecovery() {
      // From 1.15 to 0.5 against a 0.4 threshold: excess fell from 0.75 to 0.1, a real
      // improvement, and the service is still outside its objective.
      assertThat(RecoveryVerification.judge(1.15, 0.5, 0.4))
          .isEqualTo(RecoveryVerification.Outcome.PARTIALLY_RECOVERED);
    }

    @Test
    @DisplayName("an unmeasurable metric is indeterminate, never recovered")
    void unmeasurableIsIndeterminate() {
      // The single most dangerous defaulting mistake available in this system is treating "we
      // could not measure" as success. This test exists to make that specific mistake fail.
      assertThat(RecoveryVerification.judge(1.15, Double.NaN, 0.4))
          .isEqualTo(RecoveryVerification.Outcome.INDETERMINATE);
    }

    @Test
    @DisplayName("an unmeasurable metric is indeterminate even when the threshold is generous")
    void unmeasurableWithGenerousThreshold() {
      // NaN comparisons are false, so `afterValue <= threshold` would fall through to the
      // improvement branch and produce NOT_RECOVERED. That is wrong in a subtler way than
      // reporting success, and it would blame a fix that may have worked.
      assertThat(RecoveryVerification.judge(1.15, Double.NaN, Double.MAX_VALUE))
          .isEqualTo(RecoveryVerification.Outcome.INDETERMINATE);
    }

    @Test
    @DisplayName("a metric that got worse is not recovered")
    void worse() {
      assertThat(RecoveryVerification.judge(1.15, 2.4, 0.4))
          .isEqualTo(RecoveryVerification.Outcome.NOT_RECOVERED);
    }

    @Test
    @DisplayName("an incident whose before value was already under the threshold degrades safely")
    void beforeValueAlreadyBelowThreshold() {
      // Can happen when the symptom metric and the alarm threshold disagree, or when the incident
      // was raised on something else. There is no improvement to measure against, so the only
      // honest answers are recovered (if it is under now) or not recovered.
      assertThat(RecoveryVerification.judge(0.2, 0.9, 0.4))
          .isEqualTo(RecoveryVerification.Outcome.NOT_RECOVERED);
      assertThat(RecoveryVerification.judge(0.2, 0.3, 0.4))
          .isEqualTo(RecoveryVerification.Outcome.RECOVERED);
    }

    @ParameterizedTest(name = "before={0} after={1} threshold={2} is never RECOVERED")
    @CsvSource({
      "1.15, 0.41, 0.4",
      "1.15, 1.15, 0.4",
      "1.15, 100.0, 0.4",
      "0.0,  0.41, 0.4",
    })
    @DisplayName("anything still above the threshold is never reported as recovered")
    void aboveThresholdIsNeverRecovered(double before, double after, double threshold) {
      assertThat(RecoveryVerification.judge(before, after, threshold))
          .isNotEqualTo(RecoveryVerification.Outcome.RECOVERED);
    }
  }

  @Nested
  @DisplayName("what an outcome permits")
  class Consequences {

    @Test
    @DisplayName("only a full recovery permits resolving the incident")
    void onlyRecoveredResolves() {
      assertThat(verification(RecoveryVerification.Outcome.RECOVERED).permitsResolution()).isTrue();

      for (RecoveryVerification.Outcome outcome : RecoveryVerification.Outcome.values()) {
        if (outcome != RecoveryVerification.Outcome.RECOVERED) {
          assertThat(verification(outcome).permitsResolution())
              .describedAs("%s must not permit resolution", outcome)
              .isFalse();
        }
      }
    }

    @Test
    @DisplayName("every non-recovered outcome asks for a human")
    void everythingElseNeedsAHuman() {
      // Including PARTIALLY_RECOVERED. "Better, but not fixed" is a decision, and it is not one
      // this system is entitled to make on its own.
      for (RecoveryVerification.Outcome outcome : RecoveryVerification.Outcome.values()) {
        assertThat(verification(outcome).requiresHumanAttention())
            .describedAs("%s", outcome)
            .isEqualTo(outcome != RecoveryVerification.Outcome.RECOVERED);
      }
    }
  }

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("a verification with no supporting evidence is refused")
    void mustCiteEvidence() {
      // A verdict with nothing behind it is an opinion about whether the incident is over, and it
      // would be indistinguishable in the report from one backed by measurement.
      assertThatThrownBy(
              () ->
                  new RecoveryVerification(
                      IncidentId.newId(),
                      "TargetResponseTimeP99",
                      1.15,
                      0.19,
                      0.4,
                      RecoveryVerification.Outcome.RECOVERED,
                      "Latency is back to normal.",
                      List.of(),
                      Instant.now()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("opinion");
    }

    @Test
    @DisplayName("a blank summary is refused")
    void mustExplainItself() {
      assertThatThrownBy(
              () ->
                  new RecoveryVerification(
                      IncidentId.newId(),
                      "TargetResponseTimeP99",
                      1.15,
                      0.19,
                      0.4,
                      RecoveryVerification.Outcome.RECOVERED,
                      "   ",
                      List.of(EvidenceId.newId()),
                      Instant.now()))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("supporting evidence is defensively copied")
    void evidenceIsImmutable() {
      List<EvidenceId> mutable = new java.util.ArrayList<>(List.of(EvidenceId.newId()));
      RecoveryVerification verification =
          new RecoveryVerification(
              IncidentId.newId(),
              "TargetResponseTimeP99",
              1.15,
              0.19,
              0.4,
              RecoveryVerification.Outcome.RECOVERED,
              "Latency is back to normal.",
              mutable,
              Instant.now());

      mutable.add(EvidenceId.newId());

      assertThat(verification.supportingEvidence()).hasSize(1);
    }
  }

  private static RecoveryVerification verification(RecoveryVerification.Outcome outcome) {
    return new RecoveryVerification(
        IncidentId.newId(),
        "TargetResponseTimeP99",
        1.15,
        0.19,
        0.4,
        outcome,
        "Summary for " + outcome,
        List.of(EvidenceId.newId()),
        Instant.now());
  }
}
