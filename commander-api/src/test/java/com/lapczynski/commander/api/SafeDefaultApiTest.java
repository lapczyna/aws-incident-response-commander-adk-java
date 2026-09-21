package com.lapczynski.commander.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a deployment that configures nothing will do.
 *
 * <p>This is the test for the defaults, and the defaults are the security posture: no action type
 * is permitted, no resource is allowlisted, and actions are disabled entirely. A deployment in that
 * state must investigate happily and refuse to act, and it must say so rather than failing in a way
 * that looks like a bug.
 *
 * <p>It is a separate class from {@code ApprovalFlowTest} because it needs a different
 * configuration, and because the two are asking different questions: that one asks whether an
 * approval can be granted correctly, this one asks what happens when nobody has enabled anything.
 */
@DisplayName("A deployment with the shipped defaults")
class SafeDefaultApiTest extends ApiIntegrationTest {

  /**
   * Identities, as request post-processors rather than {@code @WithMockUser}.
   *
   * <p>Explicit per request, because several of these tests are about two people doing different
   * things to the same incident, and an annotation that applies to a whole method cannot express
   * that. It also puts the identity next to the call it authorises, which is where a reader looks.
   */
  private static final org.springframework.test.web.servlet.request.RequestPostProcessor RESPONDER =
      user("responder").roles("INVESTIGATOR");

  private static final org.springframework.test.web.servlet.request.RequestPostProcessor VIEWER =
      user("viewer").roles("VIEWER");

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("investigates, and refuses to act, and closes the incident saying why")
  void refusesToActWithNothingAllowlisted() throws Exception {
    String incidentId = raise();

    String body =
        mvc.perform(post("/api/incidents/{id}/investigate", incidentId).with(RESPONDER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode incident = json.readTree(body);

    // Resolved, not failed. Policy refusing a proposal is the system working correctly, and
    // recording it as a failure would train an operator to ignore failures.
    assertThat(incident.get("status").asText())
        .as("an incident whose remediation policy refuses is closed, not left in flight")
        .isEqualTo("RESOLVED");
    assertThat(incident.get("closingNote").asText())
        .as("the refusal has to say what was refused and why")
        .contains("Policy refused");
    assertThat(incident.get("terminal").asBoolean()).isTrue();

    // Nothing is waiting for a human on this incident, because nothing got past the gate.
    //
    // Scoped to this incident rather than asserting the whole queue is empty: ApprovalFlowTest
    // shares this database and deliberately leaves a proposal pending, so a global assertion here
    // would pass or fail on the order JUnit happened to pick.
    String queue =
        mvc.perform(get("/api/approvals").with(RESPONDER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    for (JsonNode pending : json.readTree(queue)) {
      assertThat(pending.get("incidentId").asText())
          .as("a refused proposal must never reach the approval queue")
          .isNotEqualTo(incidentId);
    }
  }

  @Test
  @DisplayName("records the alert even though it will refuse to act on it")
  void alwaysRecordsTheAlert() throws Exception {
    String incidentId = raise();

    mvc.perform(get("/api/incidents/{id}", incidentId).with(RESPONDER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECEIVED"))
        .andExpect(jsonPath("$.service").value("checkout"))
        .andExpect(jsonPath("$.version").value(0));
  }

  @Test
  @DisplayName("refuses an unauthenticated request rather than serving it")
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/api/incidents")).andExpect(status().isUnauthorized());
  }

  /**
   * A viewer can see the queue and cannot decide anything on it.
   *
   * <p>Both halves matter. Hiding pending approvals from someone who cannot grant them would make
   * the system harder to operate without making it any safer; letting them grant one would make the
   * approver role decorative.
   */
  @Test
  @DisplayName("lets a viewer read the approval queue and refuses their decision")
  void viewerMayReadButNotDecide() throws Exception {
    mvc.perform(get("/api/approvals").with(VIEWER)).andExpect(status().isOk());

    mvc.perform(
            post("/api/approvals/{id}/approve", "00000000-0000-0000-0000-000000000000")
                .with(VIEWER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"looks fine to me\"}"))
        .andExpect(status().isForbidden());
  }

  private String raise() throws Exception {
    String response =
        mvc.perform(
                post("/api/incidents")
                    .with(RESPONDER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"title": "Checkout p99 latency tripled",
                         "serviceName": "checkout",
                         "severity": "SEV2",
                         "metricName": "TargetResponseTimeP99",
                         "observedValue": 1450.0,
                         "recoveryThreshold": 500.0}
                        """))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return json.readTree(response).get("id").asText();
  }
}
