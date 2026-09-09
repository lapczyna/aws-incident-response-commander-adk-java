package com.lapczynski.commander.adk.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Bounds and observes an investigation.
 *
 * <p>An ADK plugin rather than per-agent callbacks, because the budget must be shared. Four
 * specialists running in parallel with a per-agent limit would collectively spend four times what
 * anyone intended; the limit that matters is the one for the whole invocation.
 *
 * <p>Three jobs:
 *
 * <ol>
 *   <li><strong>Tool-call budget.</strong> Refuses further calls once the invocation has spent its
 *       allowance, returning a structured refusal rather than throwing. An agent that hits the
 *       ceiling should report what it has, not crash the run.
 *   <li><strong>Wall-clock deadline.</strong> Independent of the call budget, because a small
 *       number of slow calls can exceed an incident's useful lifetime just as effectively as many
 *       fast ones.
 *   <li><strong>Correlation and telemetry.</strong> Puts the invocation and agent into the MDC so
 *       every log line is attributable, and records tool latency and failures.
 * </ol>
 *
 * <p>Budgets are enforced <em>here</em> rather than in the tools themselves. A tool that policed
 * its own budget could only see its own calls, and a new tool would arrive unbudgeted by default.
 */
public class InvestigationBudgetPlugin extends BasePlugin {

  private static final Logger log = LoggerFactory.getLogger(InvestigationBudgetPlugin.class);

  /** MDC keys. Correlated logging is a stated project goal, so the names are fixed here. */
  public static final String MDC_INVOCATION_ID = "invocationId";

  public static final String MDC_AGENT = "agent";
  public static final String MDC_TOOL = "tool";

  private final int maxToolCallsPerInvocation;
  private final Duration deadline;

  private final Map<String, AtomicInteger> toolCallsByInvocation = new ConcurrentHashMap<>();
  private final Map<String, Instant> startedAt = new ConcurrentHashMap<>();
  private final Map<String, Instant> toolStartedAt = new ConcurrentHashMap<>();

  public InvestigationBudgetPlugin(int maxToolCallsPerInvocation, Duration deadline) {
    // BasePlugin takes its name through the constructor and exposes it via getName(); there is no
    // name() to override.
    super("investigation-budget");
    this.maxToolCallsPerInvocation = maxToolCallsPerInvocation;
    this.deadline = deadline;
  }

  @Override
  public Maybe<com.google.genai.types.Content> beforeRunCallback(InvocationContext context) {
    startedAt.put(context.invocationId(), Instant.now());
    toolCallsByInvocation.put(context.invocationId(), new AtomicInteger());
    MDC.put(MDC_INVOCATION_ID, context.invocationId());
    log.info(
        "Investigation started: invocationId={} toolBudget={} deadline={}",
        context.invocationId(),
        maxToolCallsPerInvocation,
        deadline);
    return Maybe.empty();
  }

  @Override
  public Completable afterRunCallback(InvocationContext context) {
    Instant started = startedAt.remove(context.invocationId());
    AtomicInteger calls = toolCallsByInvocation.remove(context.invocationId());

    log.info(
        "Investigation finished: invocationId={} toolCalls={} elapsed={}",
        context.invocationId(),
        calls == null ? 0 : calls.get(),
        started == null ? "unknown" : Duration.between(started, Instant.now()));

    // Cleared explicitly. MDC is thread-local and these run on a pooled scheduler, so a stale
    // invocation id would attach itself to whatever ran next on that thread.
    MDC.remove(MDC_INVOCATION_ID);
    MDC.remove(MDC_AGENT);
    MDC.remove(MDC_TOOL);
    return Completable.complete();
  }

  @Override
  public Maybe<com.google.genai.types.Content> beforeAgentCallback(
      BaseAgent agent, CallbackContext callbackContext) {
    MDC.put(MDC_AGENT, agent.name());
    return Maybe.empty();
  }

  /**
   * Enforces the budget and the deadline.
   *
   * <p>Returning a value from this hook <em>replaces</em> the tool result, so a refusal reaches the
   * model as an ordinary tool response it can reason about. Throwing would abort the invocation and
   * discard evidence already gathered — the opposite of what a budget is for.
   */
  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {

    String invocationId = toolContext.invocationId();
    MDC.put(MDC_TOOL, tool.name());
    toolStartedAt.put(toolCallKey(invocationId, tool.name()), Instant.now());

    Instant started = startedAt.get(invocationId);
    if (started != null && Duration.between(started, Instant.now()).compareTo(deadline) > 0) {
      log.warn(
          "Tool call refused, deadline exceeded: invocationId={} tool={} deadline={}",
          invocationId,
          tool.name(),
          deadline);
      return Maybe.just(
          refusal(
              "deadline_exceeded",
              "The investigation deadline of %s has passed. Stop gathering evidence and report your "
                      .formatted(deadline)
                  + "conclusions from what you already have, noting that the investigation was cut short."));
    }

    AtomicInteger calls =
        toolCallsByInvocation.computeIfAbsent(invocationId, key -> new AtomicInteger());
    int used = calls.incrementAndGet();

    if (used > maxToolCallsPerInvocation) {
      log.warn(
          "Tool call refused, budget exhausted: invocationId={} tool={} used={} budget={}",
          invocationId,
          tool.name(),
          used,
          maxToolCallsPerInvocation);
      return Maybe.just(
          refusal(
              "budget_exhausted",
              "The tool-call budget of %d for this investigation is exhausted. Report your conclusions "
                      .formatted(maxToolCallsPerInvocation)
                  + "from the evidence already gathered, and state which questions remain unanswered."));
    }

    log.debug(
        "Tool call: invocationId={} tool={} used={}/{}",
        invocationId,
        tool.name(),
        used,
        maxToolCallsPerInvocation);
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {

    Instant started = toolStartedAt.remove(toolCallKey(toolContext.invocationId(), tool.name()));
    if (started != null) {
      log.debug(
          "Tool completed: tool={} latencyMs={} status={}",
          tool.name(),
          Duration.between(started, Instant.now()).toMillis(),
          result.getOrDefault("status", "unknown"));
    }
    MDC.remove(MDC_TOOL);
    return Maybe.empty();
  }

  /**
   * Turns a tool failure into a result the agent can carry on from.
   *
   * <p>Without this a thrown exception inside one specialist would propagate and take down the
   * whole {@code ParallelAgent}, discarding the three investigations that were succeeding.
   */
  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {

    log.warn(
        "Tool failed: invocationId={} tool={} error={}",
        toolContext.invocationId(),
        tool.name(),
        error.toString());
    MDC.remove(MDC_TOOL);

    return Maybe.just(
        Map.of(
            "status",
            "unavailable",
            "reason",
            "TOOL_ERROR",
            "detail",
            String.valueOf(error.getMessage()),
            "guidance",
            "This tool failed. Continue with the evidence you can gather and state explicitly that "
                + "this check could not be completed. Do not infer what it would have shown."));
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequestBuilder) {
    MDC.put(MDC_AGENT, callbackContext.agentName());
    return Maybe.empty();
  }

  /** Tool calls used by an invocation. Exposed for tests and for the incident record. */
  public int toolCallsUsed(String invocationId) {
    AtomicInteger calls = toolCallsByInvocation.get(invocationId);
    return calls == null ? 0 : calls.get();
  }

  private static Map<String, Object> refusal(String reason, String guidance) {
    return Map.of("status", "refused", "reason", reason, "guidance", guidance);
  }

  private static String toolCallKey(String invocationId, String toolName) {
    return invocationId + '/' + toolName;
  }
}
