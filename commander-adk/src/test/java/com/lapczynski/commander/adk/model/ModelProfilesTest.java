package com.lapczynski.commander.adk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.adk.models.BaseLlm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The model profiles, and specifically the two things they refuse to do.
 *
 * <p>Both assertions here are about start-up failures, which is the whole point. A misconfigured
 * model provider that starts anyway fails on the first investigation instead — by which time an
 * incident already exists, an operator is already waiting, and the error arrives as part of a
 * response rather than as part of a deployment.
 *
 * <p>Nothing in this class reaches a provider. Constructing the Bedrock model builds an SDK client,
 * which resolves neither credentials nor endpoints until a call is made, so the test runs with no
 * AWS account and no network.
 */
class ModelProfilesTest {

  private final ModelProfiles profiles = new ModelProfiles();

  @Nested
  @DisplayName("gemini")
  class Gemini {

    @Test
    @DisplayName("refuses to start without a key, and says where to get one")
    void refusesWithoutApiKey() {
      assertThatThrownBy(() -> profiles.geminiModel("gemini-3.5-flash-lite", ""))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GEMINI_API_KEY")
          // The message is part of the contract. An error that says only "not configured" costs
          // whoever reads it a search; this one carries the URL and the warning about free-tier
          // data handling.
          .hasMessageContaining("aistudio.google.com")
          .hasMessageContaining("ollama");
    }
  }

  @Nested
  @DisplayName("bedrock")
  class Bedrock {

    @Test
    @DisplayName("refuses to start without a region rather than silently using us-east-1")
    void refusesWithoutRegion() {
      // Spring AI's fallback when no region resolves is us-east-1, logged at debug. That is a
      // wrong answer about cost, latency and data residency, delivered quietly. This is the guard
      // that turns it into a start-up failure.
      assertThatThrownBy(() -> profiles.bedrockModel("amazon.nova-lite-v1:0", ""))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("AWS_REGION")
          .hasMessageContaining("us-east-1");
    }

    @Test
    @DisplayName("builds a model that ADK can drive, without credentials")
    void buildsThroughTheSpringAiBridge() {
      BaseLlm model = profiles.bedrockModel("amazon.nova-lite-v1:0", "eu-west-1");

      // The bridge is the claim being checked: Bedrock reaches ADK through the same SpringAI
      // adapter as Ollama, so orchestration never learns which provider it is talking to
      // (ADR-0002).
      assertThat(model).isInstanceOf(com.google.adk.models.springai.SpringAI.class);
      assertThat(model.model()).isEqualTo("amazon.nova-lite-v1:0");
    }
  }
}
