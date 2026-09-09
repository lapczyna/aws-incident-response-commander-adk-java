package com.lapczynski.commander.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.ToolConfirmation;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the JSON fidelity of the ADK {@link Event} fields that human-in-the-loop resumption depends
 * on.
 *
 * <p>A durable session service stores a serialised snapshot, while ADK's in-memory one keeps a live
 * reference. Any field that does not survive a JSON round trip is therefore invisible in every
 * in-memory test and missing in production — and the specific consequence here is an approved
 * action that silently never runs.
 *
 * <p>Deliberately free of a database, so it runs in milliseconds and pinpoints the field rather
 * than reporting that a whole workflow failed.
 */
class EventSerialisationFidelityTest {

  private static Event roundTrip(Event original) {
    return JsonBaseModel.fromJsonString(original.toJson(), Event.class);
  }

  @Test
  @DisplayName("a function call keeps its id, name and arguments")
  void functionCallSurvives() {
    Event original =
        Event.builder()
            .id(Event.generateEventId())
            .invocationId("inv-1")
            .author("remediation_executor")
            .content(
                Content.builder()
                    .role("model")
                    .parts(
                        java.util.List.of(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder()
                                        .id("call-abc")
                                        .name("executeRemediation")
                                        .args(Map.of("actionType", "ROLLBACK_DEPLOYMENT"))
                                        .build())
                                .build()))
                    .build())
            .timestamp(1L)
            .build();

    Event restored = roundTrip(original);

    assertThat(restored.functionCalls()).hasSize(1);
    assertThat(restored.functionCalls().getFirst().id())
        .as(
            "resumption matches a confirmation against this id; without it an approved action "
                + "can never be resumed")
        .contains("call-abc");
    assertThat(restored.functionCalls().getFirst().name()).contains("executeRemediation");
    assertThat(restored.functionCalls().getFirst().args().orElseThrow())
        .containsEntry("actionType", "ROLLBACK_DEPLOYMENT");
  }

  @Test
  @DisplayName("requestedToolConfirmations survives")
  void requestedToolConfirmationsSurvive() {
    ConcurrentHashMap<String, ToolConfirmation> requested = new ConcurrentHashMap<>();
    requested.put("call-abc", ToolConfirmation.builder().hint("approve the rollback").build());

    Event original =
        Event.builder()
            .id(Event.generateEventId())
            .author("remediation_executor")
            .actions(EventActions.builder().requestedToolConfirmations(requested).build())
            .timestamp(1L)
            .build();

    Event restored = roundTrip(original);

    assertThat(restored.actions().requestedToolConfirmations())
        .as(
            "confirmationRequestedIds() is built from this; losing it means no call is considered "
                + "resumable")
        .containsKey("call-abc");
  }

  @Test
  @DisplayName("longRunningToolIds survives")
  void longRunningToolIdsSurvive() {
    Event original =
        Event.builder()
            .id(Event.generateEventId())
            .author("remediation_executor")
            .longRunningToolIds(Set.of("call-abc"))
            .timestamp(1L)
            .build();

    Event restored = roundTrip(original);

    assertThat(restored.longRunningToolIds().orElse(Set.of()))
        .as("ADK sets this after building the event; it marks the call as one that pauses")
        .contains("call-abc");
  }

  @Test
  @DisplayName("the author survives, since resumption only honours calls this agent emitted")
  void authorSurvives() {
    Event original =
        Event.builder()
            .id(Event.generateEventId())
            .author("remediation_executor")
            .timestamp(1L)
            .build();

    assertThat(roundTrip(original).author()).isEqualTo("remediation_executor");
  }

  @Test
  @DisplayName("a function response keeps its id and payload")
  void functionResponseSurvives() {
    Event original =
        Event.builder()
            .id(Event.generateEventId())
            .author("user")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            com.google.genai.types.FunctionResponse.builder()
                                .id("call-abc")
                                .name("adk_request_confirmation")
                                .response(Map.of("confirmed", true))
                                .build())
                        .build()))
            .timestamp(1L)
            .build();

    Event restored = roundTrip(original);

    assertThat(restored.functionResponses()).hasSize(1);
    assertThat(restored.functionResponses().getFirst().id()).contains("call-abc");
    assertThat(restored.functionResponses().getFirst().response().orElseThrow())
        .containsEntry("confirmed", true);
  }
}
