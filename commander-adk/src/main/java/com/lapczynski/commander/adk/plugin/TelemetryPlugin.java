package com.lapczynski.commander.adk.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.lapczynski.commander.domain.cost.CostGuard;
import com.lapczynski.commander.domain.cost.ModelPricing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts what an investigation costs, and refuses to spend past the budget.
 *
 * <p>Separate from {@link InvestigationBudgetPlugin} because the two bound different things over
 * different horizons. That one caps a single invocation — tool calls and wall clock — and resets
 * every run. This one caps spending across every invocation in a calendar month, and its state
 * outlives any one incident. Merging them would mean a per-run reset silently cleared the monthly
 * total.
 *
 * <p><strong>Refusals are structured, not thrown.</strong> When the budget is exhausted the model
 * call is replaced with a response saying so, so the agent reports what it has rather than the run
 * collapsing. An investigation that stops early with partial evidence is useful; a stack trace is
 * not.
 *
 * <p>Token counts come from ADK's {@code usageMetadata}, which providers populate. When a provider
 * does not — the fake and Ollama models do not — the counts are zero and the cost is zero, which is
 * accurate rather than a fallback: those calls genuinely cost nothing per token.
 */
public class TelemetryPlugin extends BasePlugin {

  private static final Logger log = LoggerFactory.getLogger(TelemetryPlugin.class);

  /** Metric names. Fixed here so a dashboard is not chasing a rename. */
  public static final String METRIC_MODEL_CALLS = "commander.model.calls";

  public static final String METRIC_MODEL_LATENCY = "commander.model.latency";
  public static final String METRIC_MODEL_TOKENS = "commander.model.tokens";
  public static final String METRIC_MODEL_COST = "commander.model.cost.usd";
  public static final String METRIC_MODEL_REFUSED = "commander.model.refused";
  public static final String METRIC_TOOL_CALLS = "commander.tool.calls";
  public static final String METRIC_TOOL_LATENCY = "commander.tool.latency";
  public static final String METRIC_TOOL_FAILURES = "commander.tool.failures";
  public static final String METRIC_AGENT_LATENCY = "commander.agent.latency";
  public static final String METRIC_INVESTIGATIONS = "commander.investigations";

  /**
   * Cost charged for a call whose token usage the provider did not report.
   *
   * <p>Zero, and only correct because the profiles that omit usage metadata are the ones that are
   * genuinely free. A paid provider that stopped reporting usage would be silently untracked, so
   * {@link #callsWithoutUsage()} counts those and the metric is on the dashboard.
   */
  private static final BigDecimal UNKNOWN_USAGE_COST = BigDecimal.ZERO;

  private final MeterRegistry meters;
  private final ModelPricing pricing;
  private final CostGuard costGuard;
  private final Clock clock;
  private final com.lapczynski.commander.application.port.SafetyMetrics safetyMetrics;

  private final Map<String, Instant> modelCallStarted = new ConcurrentHashMap<>();
  private final Map<String, Instant> toolCallStarted = new ConcurrentHashMap<>();
  private final Map<String, Instant> agentStarted = new ConcurrentHashMap<>();

  private final java.util.concurrent.atomic.AtomicLong callsWithoutUsage =
      new java.util.concurrent.atomic.AtomicLong();

  public TelemetryPlugin(
      MeterRegistry meters, ModelPricing pricing, CostGuard costGuard, Clock clock) {
    this(
        meters,
        pricing,
        costGuard,
        clock,
        com.lapczynski.commander.application.port.SafetyMetrics.NONE);
  }

  public TelemetryPlugin(
      MeterRegistry meters,
      ModelPricing pricing,
      CostGuard costGuard,
      Clock clock,
      com.lapczynski.commander.application.port.SafetyMetrics safetyMetrics) {
    super("telemetry");
    this.meters = meters;
    this.pricing = pricing;
    this.costGuard = costGuard;
    this.clock = clock;
    this.safetyMetrics = safetyMetrics;
  }

  // ------------------------------------------------------------------ invocation

  @Override
  public Maybe<com.google.genai.types.Content> beforeRunCallback(InvocationContext context) {
    Counter.builder(METRIC_INVESTIGATIONS)
        .description("Investigations started")
        .register(meters)
        .increment();
    return Maybe.empty();
  }

  @Override
  public Completable afterRunCallback(InvocationContext context) {
    // Per-invocation keys are removed as they complete, but an invocation that ends abnormally
    // would leave entries behind. Cleared here so the maps cannot grow without bound across a
    // long-running process.
    modelCallStarted.keySet().removeIf(key -> key.startsWith(context.invocationId() + '/'));
    toolCallStarted.keySet().removeIf(key -> key.startsWith(context.invocationId() + '/'));
    agentStarted.keySet().removeIf(key -> key.startsWith(context.invocationId() + '/'));
    return Completable.complete();
  }

  // ---------------------------------------------------------------------- model

  /**
   * Checks the budget before the call, and refuses if it is spent.
   *
   * <p>The estimate uses the request's own size where the model reports it and a nominal figure
   * otherwise. It is an estimate on purpose: the exact cost is unknowable until the response
   * arrives, and a guard that waited for that would permit one unbounded call.
   */
  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequestBuilder) {

    Instant now = clock.instant();
    modelCallStarted.put(modelKey(callbackContext), now);

    if (pricing.isFree()) {
      return Maybe.empty();
    }

    CostGuard.Verdict verdict = costGuard.check(pricing.costOf(0, 0), now);
    if (verdict instanceof CostGuard.Verdict.Refused refused) {
      Counter.builder(METRIC_MODEL_REFUSED)
          .description("Model calls refused because the monthly budget is exhausted")
          .tag("agent", callbackContext.agentName())
          .register(meters)
          .increment();

      log.error(
          "Model call refused, budget exhausted: agent={} spent={} limit={}",
          callbackContext.agentName(),
          refused.spentThisMonth(),
          refused.limit());

      return Maybe.just(
          LlmResponse.builder()
              .content(
                  com.google.genai.types.Content.fromParts(
                      com.google.genai.types.Part.fromText(refused.explanation())))
              .build());
    }
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {

    String agent = callbackContext.agentName();
    Instant started = modelCallStarted.remove(modelKey(callbackContext));

    if (started != null) {
      Timer.builder(METRIC_MODEL_LATENCY)
          .description("Model call latency")
          .tag("agent", agent)
          .register(meters)
          .record(Duration.between(started, clock.instant()));
    }

    Counter.builder(METRIC_MODEL_CALLS)
        .description("Model calls made")
        .tag("agent", agent)
        .tag("model", pricing.modelId())
        .register(meters)
        .increment();

    Optional<GenerateContentResponseUsageMetadata> usage = llmResponse.usageMetadata();
    if (usage.isEmpty()) {
      callsWithoutUsage.incrementAndGet();
      safetyMetrics.modelCallUntracked();
      costGuard.record(UNKNOWN_USAGE_COST, clock.instant());
      return Maybe.empty();
    }

    long input = usage.get().promptTokenCount().orElse(0);
    long output = usage.get().candidatesTokenCount().orElse(0);

    Counter.builder(METRIC_MODEL_TOKENS)
        .tag("agent", agent)
        .tag("direction", "input")
        .register(meters)
        .increment(input);
    Counter.builder(METRIC_MODEL_TOKENS)
        .tag("agent", agent)
        .tag("direction", "output")
        .register(meters)
        .increment(output);

    BigDecimal cost = pricing.costOf(input, output);
    costGuard.record(cost, clock.instant());

    Counter.builder(METRIC_MODEL_COST)
        .description("Model spend in USD")
        .tag("model", pricing.modelId())
        .register(meters)
        .increment(cost.doubleValue());

    // No prompt or response text. This line exists to make spend attributable, and putting
    // incident content into it would put untrusted operational data into the log pipeline.
    log.debug(
        "Model call: agent={} model={} inputTokens={} outputTokens={} costUsd={}",
        agent,
        pricing.modelId(),
        input,
        output,
        cost.toPlainString());

    return Maybe.empty();
  }

  // ----------------------------------------------------------------------- tool

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    toolCallStarted.put(toolKey(toolContext.invocationId(), tool.name()), clock.instant());
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {

    recordToolLatency(tool, toolContext);

    // Tagged by the tool's own reported status rather than by whether it threw. A source that
    // answered "unavailable" is a different operational event from one that answered, and the
    // difference is exactly what an evidence gap is.
    Counter.builder(METRIC_TOOL_CALLS)
        .description("Tool calls completed")
        .tag("tool", tool.name())
        .tag("status", String.valueOf(result.getOrDefault("status", "ok")))
        .register(meters)
        .increment();

    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {

    recordToolLatency(tool, toolContext);

    Counter.builder(METRIC_TOOL_FAILURES)
        .description("Tool calls that threw")
        .tag("tool", tool.name())
        .tag("error", error.getClass().getSimpleName())
        .register(meters)
        .increment();

    // Returns empty so the budget plugin's handler still produces the agent-facing result. Two
    // plugins observing the same failure must not both try to answer it.
    return Maybe.empty();
  }

  // ---------------------------------------------------------------------- agent

  @Override
  public Maybe<com.google.genai.types.Content> beforeAgentCallback(
      BaseAgent agent, CallbackContext callbackContext) {
    agentStarted.put(agentKey(callbackContext, agent), clock.instant());
    return Maybe.empty();
  }

  @Override
  public Maybe<com.google.genai.types.Content> afterAgentCallback(
      BaseAgent agent, CallbackContext callbackContext) {

    Instant started = agentStarted.remove(agentKey(callbackContext, agent));
    if (started != null) {
      Timer.builder(METRIC_AGENT_LATENCY)
          .description("Time spent in one agent")
          .tag("agent", agent.name())
          // Buckets, not just a total: the dashboard asks for p95, and histogram_quantile over a
          // timer with no buckets renders an empty panel rather than an error.
          .publishPercentileHistogram()
          .register(meters)
          .record(Duration.between(started, clock.instant()));
    }
    return Maybe.empty();
  }

  // -------------------------------------------------------------------- exposed

  /**
   * Calls whose provider reported no token usage.
   *
   * <p>Zero is expected on the fake and local profiles. A non-zero value on a paid profile means
   * spend is going untracked, which is why it is counted rather than ignored.
   */
  public long callsWithoutUsage() {
    return callsWithoutUsage.get();
  }

  public BigDecimal spentThisMonth() {
    return costGuard.spentThisMonth(clock.instant());
  }

  // -------------------------------------------------------------------- private

  private void recordToolLatency(BaseTool tool, ToolContext toolContext) {
    Instant started = toolCallStarted.remove(toolKey(toolContext.invocationId(), tool.name()));
    if (started != null) {
      Timer.builder(METRIC_TOOL_LATENCY)
          .description("Tool call latency")
          .tag("tool", tool.name())
          .register(meters)
          .record(Duration.between(started, clock.instant()));
    }
  }

  private static String modelKey(CallbackContext context) {
    return context.invocationId() + '/' + context.agentName();
  }

  private static String agentKey(CallbackContext context, BaseAgent agent) {
    return context.invocationId() + '/' + agent.name();
  }

  private static String toolKey(String invocationId, String toolName) {
    return invocationId + '/' + toolName;
  }
}
