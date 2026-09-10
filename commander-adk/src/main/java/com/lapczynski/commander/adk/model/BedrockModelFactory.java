package com.lapczynski.commander.adk.model;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.springai.SpringAI;
import java.time.Duration;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;

/**
 * Builds the Bedrock-backed model.
 *
 * <p>Reaches ADK through the same {@code SpringAI} adapter as Ollama, which is the point of
 * ADR-0002: two providers, one integration path, and no orchestration code that knows either name.
 *
 * <p><strong>There is not an AWS SDK type in this file, deliberately.</strong> {@code
 * ArchitectureRulesTest} fails the build if the AWS SDK is referenced outside {@code
 * commander-integrations-aws}, because the simulator has to be able to satisfy the same ports with
 * no SDK involved. Region and credentials are therefore left to Spring AI's own defaults, which
 * resolve them through the SDK's standard chains — {@code AWS_REGION} and the ECS task role in
 * deployment, the developer's profile locally. That is the same resolution {@code
 * AwsClientConfiguration} relies on, arrived at without importing it.
 *
 * <p>The cost of that constraint is one silent failure mode, which the caller closes rather than
 * this class: with no region resolvable, Spring AI falls back to {@code us-east-1} and logs it at
 * debug. A demo that quietly calls a model in the wrong region is a demo with a surprising bill, so
 * {@link ModelProfiles} refuses to start unless a region is configured.
 */
final class BedrockModelFactory {

  private BedrockModelFactory() {}

  /**
   * Time allowed for a single Converse call.
   *
   * <p>Longer than the five seconds allowed for a CloudWatch read, because a model call is
   * genuinely slower, and shorter than the investigation deadline of five minutes, so that a
   * hanging provider surfaces as one failed step rather than as an investigation that ran out of
   * time with nothing to show.
   */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

  static BaseLlm create(String modelId) {
    BedrockChatOptions options =
        BedrockChatOptions.builder()
            .model(modelId)
            // The same near-deterministic setting as the Ollama profile, for the same reason: this
            // workflow is analysis, and an investigation that reaches a different conclusion on a
            // rerun of identical evidence is not one anybody should act on.
            .temperature(0.1)
            .build();

    BedrockProxyChatModel chatModel =
        BedrockProxyChatModel.builder()
            .options(options)
            .timeout(CALL_TIMEOUT)
            .connectionTimeout(Duration.ofSeconds(10))
            .build();

    return new SpringAI(chatModel, modelId);
  }
}
