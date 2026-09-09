package com.lapczynski.commander.adk.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.policy.PolicyConfiguration;
import com.lapczynski.commander.domain.remediation.ActionType;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parsers are where model output stops being prose and becomes something the system will act
 * on, so they are tested as a boundary rather than as a utility.
 *
 * <p>The theme throughout: every ambiguity resolves towards doing less. Unparseable output proposes
 * nothing, uncited claims lose confidence, invented actions fail to parse.
 */
class ParserSafetyTest {

  private static final Set<String> AVAILABLE =
      Set.of("evidence_metrics", "evidence_logs", "evidence_ecs", "evidence_changes");

  @Nested
  @DisplayName("hypothesis: citations are verified, not trusted")
  class Citations {

    @Test
    @DisplayName("a well-cited hypothesis keeps its declared confidence")
    void validCitationsPreserveConfidence() {
      var parsed =
          HypothesisParser.parse(
              """
              {"statement": "Deploy v2.4.0 caused the latency regression",
               "reasoning": "p99 stepped from 0.18s to 0.62s at the deployment time",
               "confidence": 0.88,
               "supportingEvidence": ["evidence_metrics", "evidence_changes"]}
              """,
              AVAILABLE);

      assertThat(parsed.wellFormed()).isTrue();
      assertThat(parsed.confidence().value()).isEqualTo(0.88);
      assertThat(parsed.citedSources()).containsExactly("evidence_metrics", "evidence_changes");
      assertThat(parsed.rejectedCitations()).isEmpty();
    }

    @Test
    @DisplayName("an uncited hypothesis has its confidence capped, whatever it claimed")
    void uncitedClaimsAreCapped() {
      var parsed =
          HypothesisParser.parse(
              """
              {"statement": "It is obviously the database",
               "reasoning": "It usually is",
               "confidence": 0.99,
               "supportingEvidence": []}
              """,
              AVAILABLE);

      assertThat(parsed.confidence().value())
          .as("a model must not be able to assert its way past the evidence requirement")
          .isEqualTo(HypothesisParser.UNCITED_CONFIDENCE_CAP.value());
      assertThat(parsed.hasValidCitations()).isFalse();
    }

    @Test
    @DisplayName("citing a source that never reported is recorded, not accepted")
    void fabricatedCitationsAreRejected() {
      var parsed =
          HypothesisParser.parse(
              """
              {"statement": "The X-Ray traces show a slow span",
               "reasoning": "Trace analysis",
               "confidence": 0.95,
               "supportingEvidence": ["evidence_xray", "evidence_metrics"]}
              """,
              AVAILABLE);

      assertThat(parsed.citedSources()).containsExactly("evidence_metrics");
      assertThat(parsed.rejectedCitations())
          .as(
              "a model citing evidence it never saw is a finding worth surfacing, not a detail "
                  + "to silently drop")
          .containsExactly("evidence_xray");
      assertThat(parsed.confidence().value())
          .isLessThanOrEqualTo(HypothesisParser.PARTIALLY_CITED_CONFIDENCE_CAP.value());
      assertThat(parsed.citedMissingEvidence()).isTrue();
    }

    @Test
    @DisplayName("the uncited cap sits below the policy engine's minimum confidence")
    void uncitedCapBlocksAction() {
      Confidence policyMinimum =
          PolicyConfiguration.safeDefaults("123456789012", "eu-west-1", "demo").minimumConfidence();

      assertThat(HypothesisParser.UNCITED_CONFIDENCE_CAP.value())
          .as(
              "these two numbers are related on purpose: an uncited hypothesis must be unable to "
                  + "authorise an action, and that only holds while the cap stays below the minimum")
          .isLessThan(policyMinimum.value());
    }
  }

  @Nested
  @DisplayName("hypothesis: malformed output degrades safely")
  class MalformedHypotheses {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "The cause is a bad deployment.",
          "",
          "   ",
          "{ this is not valid json",
          "{\"unexpected\": \"shape\"}"
        })
    @DisplayName("anything unparseable yields a low-confidence hypothesis rather than throwing")
    void malformedInputIsSafe(String raw) {
      var parsed = HypothesisParser.parse(raw, AVAILABLE);

      assertThat(parsed.confidence().value())
          .as(
              "an investigation that produced unreadable output has still produced something a "
                  + "human should see, but it must not be able to authorise anything")
          .isLessThanOrEqualTo(HypothesisParser.UNCITED_CONFIDENCE_CAP.value());
      assertThat(parsed.hasValidCitations()).isFalse();
    }

    @Test
    @DisplayName("JSON wrapped in prose or a code fence is still read")
    void jsonEmbeddedInProseIsExtracted() {
      var parsed =
          HypothesisParser.parse(
              """
              Here is my analysis:
              ```json
              {"statement": "Downstream timeouts", "confidence": 0.7,
               "supportingEvidence": ["evidence_logs"]}
              ```
              Hope that helps.
              """,
              AVAILABLE);

      assertThat(parsed.wellFormed())
          .as(
              "models wrap JSON in prose constantly; discarding a good diagnosis over formatting "
                  + "would be the wrong trade")
          .isTrue();
      assertThat(parsed.citedSources()).containsExactly("evidence_logs");
    }

    @Test
    @DisplayName("a percentage confidence is interpreted, not treated as certainty")
    void percentageConfidenceIsNormalised() {
      var parsed =
          HypothesisParser.parse(
              "{\"statement\": \"x\", \"confidence\": 85, \"supportingEvidence\": [\"evidence_logs\"]}",
              AVAILABLE);

      assertThat(parsed.confidence().value()).isEqualTo(0.85);
    }

    @Test
    @DisplayName("worded confidence is understood")
    void wordedConfidenceIsUnderstood() {
      var high =
          HypothesisParser.parse(
              "{\"statement\":\"x\",\"confidence\":\"high\",\"supportingEvidence\":[\"evidence_logs\"]}",
              AVAILABLE);
      var low =
          HypothesisParser.parse(
              "{\"statement\":\"x\",\"confidence\":\"low\",\"supportingEvidence\":[\"evidence_logs\"]}",
              AVAILABLE);

      assertThat(high.confidence().value()).isGreaterThan(low.confidence().value());
    }
  }

  @Nested
  @DisplayName("remediation: anything wrong proposes nothing")
  class Proposals {

    @Test
    @DisplayName("a well-formed proposal parses into a typed action")
    void validProposalParses() {
      var action =
          RemediationProposalParser.parse(
                  """
                  {"actionType": "ROLLBACK_DEPLOYMENT",
                   "target": {"arn": "arn:aws:ecs:eu-west-1:123456789012:service/demo/checkout",
                              "accountId": "123456789012", "region": "eu-west-1",
                              "environment": "demo", "resourceType": "ecs:service"},
                   "arguments": {"taskDefinition": "checkout:41"},
                   "humanDescription": "Roll back checkout to task definition 41"}
                  """)
              .orElseThrow();

      assertThat(action.type()).isEqualTo(ActionType.ROLLBACK_DEPLOYMENT);
      assertThat(action.target().accountId()).isEqualTo("123456789012");
      assertThat(action.arguments()).containsEntry("taskDefinition", "checkout:41");
    }

    @Test
    @DisplayName("an invented action type fails to parse")
    void inventedActionIsRefused() {
      assertThat(
              RemediationProposalParser.parse(
                  """
                  {"actionType": "DELETE_ALL_RESOURCES",
                   "target": {"arn": "arn:aws:ecs:eu-west-1:123456789012:service/demo/checkout",
                              "accountId": "123456789012", "region": "eu-west-1",
                              "environment": "demo"}}
                  """))
          .as(
              "the closed enum is the cheapest defence against excessive agency, and it acts "
                  + "before any policy rule is consulted")
          .isEmpty();
    }

    @Test
    @DisplayName("actionType NONE proposes nothing, which is a valid outcome")
    void noneProposesNothing() {
      assertThat(RemediationProposalParser.parse("{\"actionType\": \"NONE\"}"))
          .as(
              "declining to act must be expressible, or the system will always find something "
                  + "to do")
          .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "",
          "I recommend rolling back the deployment.",
          "{ not json",
          "{\"actionType\": \"ROLLBACK_DEPLOYMENT\"}",
          "{\"actionType\": \"ROLLBACK_DEPLOYMENT\", \"target\": {}}",
          "{\"actionType\": \"ROLLBACK_DEPLOYMENT\", \"target\": {\"arn\": \"not-an-arn\","
              + " \"accountId\": \"1\", \"region\": \"r\", \"environment\": \"e\"}}"
        })
    @DisplayName("anything incomplete or malformed proposes nothing")
    void malformedProposalsAreRefused(String raw) {
      assertThat(RemediationProposalParser.parse(raw))
          .as(
              "guessing what a model meant would be the easiest route to executing something "
                  + "nobody proposed")
          .isEmpty();
    }

    @Test
    @DisplayName("nested argument values are dropped rather than flattened")
    void nestedArgumentsAreDropped() {
      var action =
          RemediationProposalParser.parse(
                  """
                  {"actionType": "SCALE_ECS_SERVICE",
                   "target": {"arn": "arn:aws:ecs:eu-west-1:123456789012:service/demo/checkout",
                              "accountId": "123456789012", "region": "eu-west-1",
                              "environment": "demo", "resourceType": "ecs:service"},
                   "arguments": {"desiredCount": "3", "nested": {"a": "b"}},
                   "humanDescription": "Scale to 3"}
                  """)
              .orElseThrow();

      assertThat(action.arguments())
          .as(
              "the approval fingerprint is computed over the canonical form, and a nested "
                  + "structure has no unambiguous one")
          .containsOnlyKeys("desiredCount");
    }
  }
}
