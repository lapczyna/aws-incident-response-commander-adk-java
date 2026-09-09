package com.lapczynski.commander.testing;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A deterministic {@link BaseLlm} that returns a scripted sequence of responses.
 *
 * <p>This is what lets the default build run with no model API key, no network and no cost, and it
 * is the foundation of the golden-scenario harness (ADR-0009). Agent behaviour can only be
 * regression-tested if the model's contribution is fixed; otherwise every assertion has to be
 * loosened until it stops proving anything.
 *
 * <p>The fake also <em>records</em> every request it receives. That matters as much as the scripted
 * replies: it is how a test asserts which tools were offered to the model, what instruction an
 * agent was given, and — for the adversarial suite — that untrusted evidence reached the prompt
 * wrapped in its delimiters rather than raw.
 *
 * <p>Note what this does and does not prove. It verifies that the <em>system around</em> the model
 * behaves correctly given a model response. It says nothing about whether a real model would
 * produce that response; that is a different question, answered by the opt-in {@code
 * external-model} tests.
 */
public final class FakeLlm extends BaseLlm {

  /** One scripted model turn. */
  public sealed interface Turn {

    /** A plain text reply. */
    record Text(String text) implements Turn {
      public Text {
        Objects.requireNonNull(text, "text must not be null");
      }
    }

    /** A request to call a tool. */
    record ToolCall(String toolName, Map<String, Object> arguments) implements Turn {
      public ToolCall {
        Objects.requireNonNull(toolName, "toolName must not be null");
        arguments = Map.copyOf(arguments);
      }
    }
  }

  private final Deque<Turn> script = new ArrayDeque<>();
  private final List<LlmRequest> received = new CopyOnWriteArrayList<>();
  private final String fallbackText;

  /**
   * When set, replaces the ordered script with a rule: call each offered tool exactly once, then
   * summarise.
   *
   * <p>Needed for parallel tests. Four specialists sharing one fake consume a single ordered script
   * in whatever order the scheduler happens to run them, so a positional script makes assertions
   * depend on thread timing. Deciding from the request instead is order-independent, and it also
   * models what an agent actually does: call its tools, then report.
   */
  private final String autonomousSummary;

  private FakeLlm(
      String modelName, List<Turn> turns, String fallbackText, String autonomousSummary) {
    super(modelName);
    this.script.addAll(turns);
    this.fallbackText = fallbackText;
    this.autonomousSummary = autonomousSummary;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * A fake that answers everything with one line. Useful when the reply is not what is under test.
   */
  public static FakeLlm alwaysSaying(String text) {
    return builder().model("fake-model").fallback(text).build();
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    received.add(llmRequest);

    if (autonomousSummary != null) {
      return Flowable.just(autonomousTurn(llmRequest));
    }

    Turn turn = script.poll();
    if (turn == null) {
      // Running past the end of a script is a test authoring mistake, not a model failure, but
      // throwing here would surface deep inside ADK's flow as something unrecognisable. A clearly
      // marked fallback keeps the failure readable at the assertion instead.
      return Flowable.just(textResponse(fallbackText));
    }

    return Flowable.just(
        switch (turn) {
          case Turn.Text text -> textResponse(text.text());
          case Turn.ToolCall call -> toolCallResponse(call);
        });
  }

  /**
   * Picks the first offered tool that has not yet produced a response in this conversation.
   *
   * <p>Reads the conversation rather than counting calls, so it behaves correctly no matter how the
   * scheduler interleaves parallel agents.
   */
  private LlmResponse autonomousTurn(LlmRequest llmRequest) {
    java.util.Set<String> alreadyCalled = new java.util.HashSet<>();
    llmRequest
        .contents()
        .forEach(
            content ->
                content
                    .parts()
                    .ifPresent(
                        parts ->
                            parts.forEach(
                                part ->
                                    part.functionResponse()
                                        .flatMap(response -> response.name())
                                        .ifPresent(alreadyCalled::add))));

    for (String toolName : llmRequest.tools().keySet()) {
      if (!alreadyCalled.contains(toolName)) {
        return toolCallResponse(new Turn.ToolCall(toolName, defaultArgsFor(toolName)));
      }
    }
    return textResponse(autonomousSummary);
  }

  /**
   * Plausible arguments for the project's tools.
   *
   * <p>Every tool here takes a service name and optional narrowing parameters, so a small map
   * covers all of them. A fake that guessed argument names would fail schema validation and look
   * like a framework problem.
   */
  private static Map<String, Object> defaultArgsFor(String toolName) {
    Map<String, Object> args = new java.util.LinkedHashMap<>();
    args.put("serviceName", "checkout");
    switch (toolName) {
      case "queryServiceMetric" -> args.put("metricName", "TargetResponseTimeP99");
      case "queryServiceLogs" -> args.put("pattern", "error|exception|timeout|failed");
      default -> {
        // The remaining tools need only the service name.
      }
    }
    return args;
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException(
        "FakeLlm does not support live/bidi connections; this project's workflow is request-response");
  }

  private static LlmResponse textResponse(String text) {
    return LlmResponse.builder()
        .content(Content.builder().role("model").parts(List.of(Part.fromText(text))).build())
        .turnComplete(true)
        .build();
  }

  private static LlmResponse toolCallResponse(Turn.ToolCall call) {
    return LlmResponse.builder()
        .content(
            Content.builder()
                .role("model")
                .parts(List.of(Part.fromFunctionCall(call.toolName(), call.arguments())))
                .build())
        .build();
  }

  // ------------------------------------------------------------- inspection

  /** Every request the model received, in order. */
  public List<LlmRequest> receivedRequests() {
    return List.copyOf(received);
  }

  /** How many turns of the script are still unused. Non-zero at the end usually means a bug. */
  public int remainingTurns() {
    return script.size();
  }

  /**
   * Everything the model was shown, as text.
   *
   * <p>Includes tool results, not just conversational text. Tool output arrives as {@code
   * functionResponse} parts rather than {@code text} parts, so a helper that read only the latter
   * would silently return an empty string for exactly the content the adversarial tests care about
   * — and those tests would pass while proving nothing.
   *
   * <p>Used to assert that untrusted evidence arrived wrapped in its delimiters, and that no
   * credential material ever reaches a prompt.
   */
  public String allPromptText() {
    StringBuilder sb = new StringBuilder();
    for (LlmRequest request : received) {
      request
          .contents()
          .forEach(
              content ->
                  content.parts().ifPresent(parts -> parts.forEach(part -> appendPart(sb, part))));
    }
    return sb.toString();
  }

  private static void appendPart(StringBuilder sb, Part part) {
    part.text().ifPresent(text -> sb.append(text).append('\n'));

    // Tool results. The response map is rendered rather than inspected field by field, because a
    // test asserting "this content reached the model" should not depend on the shape a particular
    // tool happened to return.
    part.functionResponse()
        .ifPresent(
            response -> {
              sb.append("[functionResponse ").append(response.name().orElse("?")).append("] ");
              response.response().ifPresent(map -> sb.append(map));
              sb.append('\n');
            });

    part.functionCall()
        .ifPresent(
            call -> {
              sb.append("[functionCall ").append(call.name().orElse("?")).append("] ");
              call.args().ifPresent(args -> sb.append(args));
              sb.append('\n');
            });
  }

  /** Names of the tools offered to the model on the most recent request. */
  public List<String> toolsOfferedOnLastRequest() {
    if (received.isEmpty()) {
      return List.of();
    }
    return new ArrayList<>(received.getLast().tools().keySet());
  }

  public static final class Builder {
    private String model = "fake-model";
    private final List<Turn> turns = new ArrayList<>();
    private String fallback = "[FakeLlm: script exhausted]";
    private String autonomousSummary;

    public Builder model(String model) {
      this.model = model;
      return this;
    }

    /** Adds a scripted text reply. */
    public Builder respondsWith(String text) {
      turns.add(new Turn.Text(text));
      return this;
    }

    /** Adds a scripted tool call. */
    public Builder callsTool(String toolName, Map<String, Object> arguments) {
      turns.add(new Turn.ToolCall(toolName, arguments));
      return this;
    }

    /**
     * Calls every tool the agent offers, exactly once, then returns {@code summary}.
     *
     * <p>Order-independent, so it works for agents running in parallel. Use it when the point of a
     * test is that tools were exercised rather than that a particular sequence occurred.
     */
    public Builder callsEachOfferedToolOnceThenSays(String summary) {
      this.autonomousSummary = summary;
      return this;
    }

    /** Returned once the script runs out. */
    public Builder fallback(String text) {
      this.fallback = text;
      return this;
    }

    public FakeLlm build() {
      return new FakeLlm(model, turns, fallback, autonomousSummary);
    }
  }
}
