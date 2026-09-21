package com.lapczynski.commander.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A deployment that has enabled exactly one action, against exactly one resource.
 *
 * <p>The other side of {@link SafeDefaultApiTest}. That one asks what a system which permits
 * nothing does; this one asks what happens when a proposal clears every policy rule and reaches a
 * human — which is the only configuration in which the approval machinery is exercised at all.
 *
 * <p><strong>Dry run stays on.</strong> The point here is the decision, not the effect: whether the
 * right person can authorise the right fingerprint, whether the wrong person is refused, and
 * whether the incident ends up where the state machine says it should. Turning execution on would
 * add an AWS call to a test about who is allowed to press a button.
 *
 * <p>The allowlisted ARN and the action type are the ones {@code ScriptedDemoLlm} proposes. That
 * coupling is deliberate and is the reason this test can exist without a real model: the fake model
 * ships with the application, so the proposal under test is the proposal the demo actually makes.
 */
@TestPropertySource(
    properties = {
      "commander.policy.actions-enabled=true",
      "commander.policy.dry-run=true",
      "commander.policy.allowed-actions=ROLLBACK_DEPLOYMENT",
      "commander.policy.allowed-resource-arns="
          + "arn:aws:ecs:eu-west-1:123456789012:service/commander/checkout",
      "commander.policy.minimum-confidence=0.7"
    })
@DisplayName("A deployment that permits one action against one resource")
class ApprovalFlowTest extends ApiIntegrationTest {

  private static final RequestPostProcessor RESPONDER = user("responder").roles("INVESTIGATOR");
  private static final RequestPostProcessor APPROVER = user("approver").roles("APPROVER");
  private static final RequestPostProcessor SECOND_APPROVER =
      user("second-approver").roles("APPROVER");
  private static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  /**
   * The path the whole system exists to make safe.
   *
   * <p>Investigate, stop, let a human read the proposal, authorise it, act once, measure. The
   * assertion that matters most is the middle one: the incident is parked in {@code
   * AWAITING_APPROVAL} with a request carrying a fingerprint, and it stays there until somebody
   * decides.
   */
  @Test
  @DisplayName("investigates, stops for a human, and acts only once one has decided")
  void reachesAHumanAndWaits() throws Exception {
    String incidentId = raise(RESPONDER);

    JsonNode investigated = investigate(incidentId);
    assertThat(investigated.get("status").asText())
        .as("a permitted proposal must park the incident rather than act on it")
        .isEqualTo("AWAITING_APPROVAL");
    assertThat(investigated.get("awaitingHuman").asBoolean()).isTrue();

    JsonNode approval = pendingApprovalFor(incidentId);
    assertThat(approval.get("action").asText()).isEqualTo("ROLLBACK_DEPLOYMENT");
    assertThat(approval.get("fingerprint").asText())
        .as("an approver decides a specific fingerprint, so one has to be shown")
        .isNotBlank();

    // The version the approval binds to is the version the incident is holding while it waits.
    // If these ever disagree the approval is void the moment it is created.
    assertThat(approval.get("incidentVersion").asLong())
        .isEqualTo(investigated.get("version").asLong());

    JsonNode decided = approve(approval.get("id").asText());

    // FAILED, and that is the correct outcome rather than a disappointing one.
    //
    // The action ran as a dry run and recovery was then measured, which is the whole point: the
    // verdict comes from comparing the metric against the threshold captured when the alert was
    // raised, not from asking anything whether its own fix worked. Within the scenario's timeline
    // there is not yet enough post-remediation data, so the verdict is INDETERMINATE — and
    // IncidentService resolves only on RECOVERED. Everything else fails the incident and asks for
    // a human, because treating an unmeasurable result as success is the defaulting mistake
    // ADR-0010 exists to prevent.
    //
    // Asserting "not RESOLVED" as well as "FAILED" is deliberate: a change that made an
    // unmeasurable verification resolve would still satisfy a looser assertion about reaching a
    // terminal state, and that is precisely the regression worth catching.
    assertThat(decided.get("status").asText())
        .as("an unmeasurable recovery must never resolve the incident")
        .isEqualTo("FAILED");
    assertThat(decided.get("terminal").asBoolean()).isTrue();
    assertThat(decided.get("closingNote").asText())
        .as("the incident has to say why it could not be judged")
        .contains("recovery");

    // Scoped to this incident rather than asserting the queue is empty: these tests share one
    // database, and a sibling that deliberately leaves a proposal pending would otherwise fail
    // this one depending on the order JUnit happened to run them in.
    assertThat(pendingQueueFor(incidentId)).as("a decided proposal must leave the queue").isEmpty();
  }

  /**
   * The refusal the whole separation-of-duties rule exists for.
   *
   * <p>An approver who opened the incident is refused on the incident they opened, and is not
   * refused on one somebody else opened — both halves, because a rule that simply blocked this
   * approver everywhere would pass the first assertion and be useless.
   */
  @Test
  @DisplayName("refuses an approver acting on an incident they opened themselves")
  void theOpenerCannotApprove() throws Exception {
    String theirOwn = raise(APPROVER);
    investigate(theirOwn);
    String ownApproval = pendingApprovalFor(theirOwn).get("id").asText();

    mvc.perform(
            post("/api/approvals/{id}/approve", ownApproval)
                .with(APPROVER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"I raised it and I am sure\"}"))
        .andExpect(status().isForbidden());

    // Still pending, read back by id rather than from the queue: a refusal that quietly consumed
    // the request would drop it out of the queue and make its absence look like success.
    assertThat(approvalById(ownApproval).get("status").asText()).isEqualTo("PENDING");

    // And somebody else may decide it.
    mvc.perform(
            post("/api/approvals/{id}/approve", ownApproval)
                .with(SECOND_APPROVER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"reviewed the evidence\"}"))
        .andExpect(status().isOk());
  }

  /**
   * A rejection closes the incident and executes nothing.
   *
   * <p>{@code REJECTED} rather than {@code RESOLVED}: a human declining a proposed change is a
   * distinct outcome from the system deciding there was nothing to do, and a postmortem that
   * conflated them would misrepresent every incident a person stopped.
   */
  @Test
  @DisplayName("closes the incident as rejected when a human declines")
  void aRejectionClosesTheIncident() throws Exception {
    String incidentId = raise(RESPONDER);
    investigate(incidentId);
    String approvalId = pendingApprovalFor(incidentId).get("id").asText();

    String body =
        mvc.perform(
                post("/api/approvals/{id}/reject", approvalId)
                    .with(APPROVER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":\"rolling back would lose the in-flight carts\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode incident = json.readTree(body);
    assertThat(incident.get("status").asText()).isEqualTo("REJECTED");
    assertThat(incident.get("terminal").asBoolean()).isTrue();
  }

  /**
   * The same request cannot be decided twice.
   *
   * <p>Not a race guard — this is sequential — but the rule that makes one true. An approval is a
   * single authorisation of a single fingerprint, and a second decision on it would be a second
   * authorisation nobody granted.
   */
  @Test
  @DisplayName("refuses a second decision on a request that is already decided")
  void oneDecisionPerRequest() throws Exception {
    String incidentId = raise(RESPONDER);
    investigate(incidentId);
    String approvalId = pendingApprovalFor(incidentId).get("id").asText();

    mvc.perform(
            post("/api/approvals/{id}/reject", approvalId)
                .with(APPROVER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"no\"}"))
        .andExpect(status().isOk());

    mvc.perform(
            post("/api/approvals/{id}/approve", approvalId)
                .with(SECOND_APPROVER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"actually yes\"}"))
        .andExpect(status().isConflict());
  }

  /** A viewer may read the queue and may not decide anything on it. */
  @Test
  @DisplayName("lets a viewer read a pending proposal and refuses their decision")
  void aViewerMayReadButNotDecide() throws Exception {
    String incidentId = raise(RESPONDER);
    investigate(incidentId);
    String approvalId = pendingApprovalFor(incidentId).get("id").asText();

    mvc.perform(get("/api/approvals/{id}", approvalId).with(VIEWER)).andExpect(status().isOk());

    mvc.perform(
            post("/api/approvals/{id}/approve", approvalId)
                .with(VIEWER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"looks fine\"}"))
        .andExpect(status().isForbidden());
  }

  // ------------------------------------------------------------------ helpers

  private String raise(RequestPostProcessor who) throws Exception {
    String response =
        mvc.perform(
                post("/api/incidents")
                    .with(who)
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

  private JsonNode investigate(String incidentId) throws Exception {
    String body =
        mvc.perform(
                post("/api/incidents/{id}/investigate", incidentId)
                    .with(RESPONDER)
                    .param("metricName", "TargetResponseTimeP99")
                    .param("observedValue", "1450.0")
                    .param("recoveryThreshold", "500.0"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return json.readTree(body);
  }

  private JsonNode approve(String approvalId) throws Exception {
    String body =
        mvc.perform(
                post("/api/approvals/{id}/approve", approvalId)
                    .with(APPROVER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":\"evidence is consistent; rolling back\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return json.readTree(body);
  }

  private JsonNode pendingApprovalFor(String incidentId) throws Exception {
    List<JsonNode> mine = pendingQueueFor(incidentId);
    assertThat(mine)
        .as("exactly one proposal should be waiting on incident %s", incidentId)
        .hasSize(1);
    return mine.get(0);
  }

  /** The pending proposals for one incident, which is the only slice of the queue a test owns. */
  private List<JsonNode> pendingQueueFor(String incidentId) throws Exception {
    List<JsonNode> mine = new ArrayList<>();
    for (JsonNode candidate : pendingQueue()) {
      if (incidentId.equals(candidate.get("incidentId").asText())) {
        mine.add(candidate);
      }
    }
    return mine;
  }

  private JsonNode approvalById(String approvalId) throws Exception {
    String body =
        mvc.perform(get("/api/approvals/{id}", approvalId).with(APPROVER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body);
  }

  private JsonNode pendingQueue() throws Exception {
    String body =
        mvc.perform(get("/api/approvals").with(APPROVER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body);
  }
}
