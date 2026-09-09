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
}
