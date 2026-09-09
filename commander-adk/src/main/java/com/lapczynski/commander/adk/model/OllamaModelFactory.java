package com.lapczynski.commander.adk.model;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.springai.SpringAI;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * Builds the Ollama-backed model.
 *
 * <p>Separated from {@link ModelProfiles} so that the Spring AI and Ollama classes are only loaded
 * when the profile is active. Both dependencies are declared {@code optional}, so a deployment that
 * never uses Ollama does not have to carry them — but a class that referenced them directly would
 * fail to load regardless of profile.
 */
final class OllamaModelFactory {

  private OllamaModelFactory() {}

  static BaseLlm create(String modelName, String baseUrl) {
    OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();

    OllamaChatModel chatModel =
        OllamaChatModel.builder()
            .ollamaApi(api)
            // Spring AI 2.0 renamed OllamaOptions to OllamaChatOptions and defaultOptions()
            // to options(). Verified against spring-ai-ollama-2.0.1, not assumed from 1.x docs.
            .options(
                OllamaChatOptions.builder()
                    .model(modelName)
                    // Deterministic-leaning. This workflow is analysis, not prose: a creative
                    // temperature produces investigations that differ run to run for no benefit.
                    .temperature(0.1)
                    .build())
            .build();

    return new SpringAI(chatModel, modelName);
  }
}
