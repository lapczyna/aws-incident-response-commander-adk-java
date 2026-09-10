package com.lapczynski.commander.adk.model;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Selects the model implementation by Spring profile.
 *
 * <p>Everything downstream depends on ADK's {@link BaseLlm}, so orchestration never names a
 * provider (ADR-0002). Adding a provider means adding a bean here.
 *
 * <p>There is deliberately no default bean. A deployment must state which model it is using; a
 * silent fallback to a paid API, or to a fake in production, are both worse than a startup failure
 * that says which profile to activate.
 */
@Configuration
public class ModelProfiles {

  private static final Logger log = LoggerFactory.getLogger(ModelProfiles.class);

  /**
   * Gemini through the native ADK integration.
   *
   * <p>The key is read from the environment locally and from Secrets Manager when deployed; it is
   * never written to a property file and never reaches a prompt.
   */
  @Bean
  @Profile("gemini")
  public BaseLlm geminiModel(
      @Value("${commander.model.gemini.name:gemini-3.5-flash-lite}") String modelName,
      @Value("${GEMINI_API_KEY:}") String apiKey) {

    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          """
          The gemini profile is active but GEMINI_API_KEY is not set.
          Get a key from https://aistudio.google.com/apikey and export it:
            export GEMINI_API_KEY=...
          Note that free-tier prompts may be used to improve Google's products. Do not send real \
          incident data through the free tier; use the ollama profile if the data cannot leave \
          your machine.
          """);
    }

    log.info("Model profile: gemini, model={}", modelName);
    return new Gemini(modelName, apiKey);
  }

  /**
   * A fully local model through Ollama.
   *
   * <p>Reaches ADK via the Spring AI bridge rather than a native connector, because ADK Java has no
   * Ollama integration of its own. {@code SpringAI} adapts any Spring AI {@code ChatModel} onto
   * {@code BaseLlm}, and ADK's own test suite exercises exactly this path, so it is a supported
   * route rather than an improvisation. See ADR-0002.
   */
  @Bean
  @Profile("ollama")
  public BaseLlm ollamaModel(
      @Value("${commander.model.ollama.name:qwen3:4b}") String modelName,
      @Value("${commander.model.ollama.base-url:http://localhost:11434}") String baseUrl) {

    log.info("Model profile: ollama, model={} baseUrl={}", modelName, baseUrl);
    return OllamaModelFactory.create(modelName, baseUrl);
  }

  /**
   * Amazon Nova Lite through Bedrock Converse.
   *
   * <p>The cheapest of the three real providers at $0.06/$0.24 per million tokens, and the only one
   * that needs no credential of its own: on Fargate it authenticates as the task role, which is
   * also the identity IAM scopes to exactly one model ARN. There is nothing to rotate and nothing
   * to leak.
   *
   * <p>The region check is the interesting part. Spring AI resolves the region through the SDK's
   * default chain and, if that produces nothing, falls back to {@code us-east-1} with a debug log.
   * Silently calling a model in another region is a wrong answer about cost, latency and data
   * residency at once, so this refuses to start instead. {@code AWS_REGION} is set by ECS and by
   * every sane local profile; if it is absent, saying so now is cheaper than discovering it from a
   * bill.
   */
  @Bean
  @Profile("bedrock")
  public BaseLlm bedrockModel(
      @Value("${commander.model.bedrock.name:amazon.nova-lite-v1:0}") String modelId,
      @Value("${commander.aws.region:}") String region) {

    if (region == null || region.isBlank()) {
      throw new IllegalStateException(
          """
          The bedrock profile is active but no AWS region is configured.
          Set AWS_REGION, or commander.aws.region, to the region the model is enabled in:
            export AWS_REGION=eu-west-1
          Without it Spring AI would fall back to us-east-1 and call a model in a region you did \
          not choose.
          """);
    }

    log.info("Model profile: bedrock, model={} region={}", modelId, region);
    return BedrockModelFactory.create(modelId);
  }
}
