package com.lapczynski.commander.adk.approval;

import com.google.adk.flows.llmflows.Functions;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the message that resumes an invocation waiting on a human.
 *
 * <p>ADK's human-in-the-loop protocol is not obvious from the API surface, so it is captured here
 * once rather than reconstructed at each call site. When a tool marked {@code requireConfirmation}
 * runs, ADK:
 *
 * <ol>
 *   <li>emits a function <em>response</em> for the original tool call carrying {@code "error":
 *       "requires confirmation"},
 *   <li>emits a function <em>call</em> named {@code adk_request_confirmation} whose arguments hold
 *       the original call, and
 *   <li>ends the invocation.
 * </ol>
 *
 * <p>To resume, the caller sends a function <em>response</em> to that {@code
 * adk_request_confirmation} call. {@code RequestConfirmationLlmRequestProcessor} then reconstructs
 * the original tool call and lets it proceed.
 *
 * <p>The identifier that matters is the id of the <strong>confirmation call</strong>, not of the
 * original tool call. Getting those two confused produces a resume that is silently ignored: the
 * invocation restarts, finds no matching confirmation, and asks for approval again. That is why
 * {@code approval_requests.adk_function_call_id} stores the confirmation call's id specifically.
 */
public final class ApprovalResumption {

  private ApprovalResumption() {}

  /**
   * The message that resumes a confirmed action.
   *
   * @param confirmationCallId the id of the {@code adk_request_confirmation} function call, taken
   *     from the persisted approval request
   */
  public static Content confirm(String confirmationCallId) {
    return response(confirmationCallId, true);
  }

  /**
   * The message that resumes a <em>rejected</em> action.
   *
   * <p>Sent rather than simply abandoning the invocation, so the agent learns the decision and can
   * record it, instead of the run being left dangling with no explanation in its own event trail.
   */
  public static Content decline(String confirmationCallId) {
    return response(confirmationCallId, false);
  }

  private static Content response(String confirmationCallId, boolean confirmed) {
    Objects.requireNonNull(confirmationCallId, "confirmationCallId must not be null");

    return Content.fromParts(
        Part.builder()
            .functionResponse(
                FunctionResponse.builder()
                    .id(confirmationCallId)
                    .name(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)
                    .response(Map.of("confirmed", confirmed))
                    .build())
            .build());
  }

  /**
   * Extracts the confirmation call's id from an event, if it carries one.
   *
   * <p>Used when an invocation pauses, to record which call a later approval must answer.
   */
  public static java.util.Optional<String> confirmationCallId(com.google.adk.events.Event event) {
    return event
        .content()
        .flatMap(Content::parts)
        .flatMap(
            parts ->
                parts.stream()
                    .map(Part::functionCall)
                    .flatMap(java.util.Optional::stream)
                    .filter(
                        call ->
                            Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME.equals(
                                call.name().orElse("")))
                    .map(call -> call.id().orElse(null))
                    .filter(Objects::nonNull)
                    .findFirst());
  }
}
