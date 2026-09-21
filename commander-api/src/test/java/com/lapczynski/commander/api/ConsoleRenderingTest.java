package com.lapczynski.commander.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lapczynski.commander.api.security.CurrentActor;
import com.lapczynski.commander.api.security.SecurityConfiguration;
import com.lapczynski.commander.api.web.ConsoleController;
import com.lapczynski.commander.application.IncidentService;
import com.lapczynski.commander.application.report.IncidentReportService;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import com.lapczynski.commander.domain.remediation.RiskLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * What the console actually renders.
 *
 * <p>A web-layer test with the domain mocked, deliberately: this is asking whether the templates
 * put the right facts on the screen and show the decision controls to the right people, and that
 * question does not need PostgreSQL, an agent runtime or Docker to answer. The approval flow itself
 * is tested against the real stack in {@code ApprovalFlowTest}.
 *
 * <p>The assertions are on content rather than on a template name. A test that asserted the view
 * resolved to {@code console} would pass against a page that rendered every field blank, and the
 * failure mode that matters here is an approver deciding an action whose target they could not see.
 */
@WebMvcTest(ConsoleController.class)
// CurrentActor is imported rather than mocked: deriving a domain role from an authentication is
// exactly what decides whether the approve button renders, and a mock would assert nothing.
@Import({
  SecurityConfiguration.class,
  CurrentActor.class,
  ConsoleRenderingTest.WebSecurityForTheSlice.class
})
@ActiveProfiles({"fake", "simulator", "local-identity"})
@DisplayName("The operator console")
class ConsoleRenderingTest {

  /**
   * What the application gets from Boot's autoconfiguration and a slice does not.
   *
   * <p>{@code SecurityConfiguration} declares filter chains but not {@code @EnableWebSecurity},
   * deliberately: in the running application Boot supplies it, and a deployment that somehow
   * activated neither identity profile still gets Boot's deny-by-default chain rather than none.
   * Putting the annotation on the production class would remove that fallback to make a test
   * easier, so the test brings its own.
   */
  @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
  @org.springframework.boot.test.context.TestConfiguration
  static class WebSecurityForTheSlice {}

  private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");
  private static final IncidentId INCIDENT = IncidentId.newId();

  private static final RequestPostProcessor APPROVER = user("approver").roles("APPROVER");
  private static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");

  @Autowired private MockMvc mvc;

  @MockitoBean private IncidentService incidents;
  @MockitoBean private IncidentReportService reports;

  @Test
  @DisplayName("shows an approver the action, its target and the version the approval binds to")
  void rendersTheProposalAnApproverHasToDecide() throws Exception {
    given(incidents.openIncidents(anyInt())).willReturn(List.of(incident()));
    given(incidents.pendingApprovals()).willReturn(List.of(approval()));

    mvc.perform(get("/console").with(APPROVER))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ROLLBACK_DEPLOYMENT")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "arn:aws:ecs:eu-west-1:123456789012:service/commander/checkout")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Approve")));
  }

  /**
   * A viewer sees the queue and is offered nothing to press.
   *
   * <p>The server refuses their decision regardless — {@code @PreAuthorize} on the controller, and
   * {@code ApprovalService} behind it — so this is about not offering an action that will be
   * rejected. Both halves are the point: hiding the queue from a viewer would make the system
   * harder to operate without making it safer.
   */
  @Test
  @DisplayName("offers a viewer no decision controls, while still showing them the queue")
  void aViewerSeesTheQueueAndNoButtons() throws Exception {
    given(incidents.openIncidents(anyInt())).willReturn(List.of(incident()));
    given(incidents.pendingApprovals()).willReturn(List.of(approval()));

    String html =
        mvc.perform(get("/console").with(VIEWER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.assertj.core.api.Assertions.assertThat(html)
        .as("the proposal is still readable")
        .contains("ROLLBACK_DEPLOYMENT");
    org.assertj.core.api.Assertions.assertThat(html)
        .as("a role that cannot decide must not be shown a decision control")
        .doesNotContain("/approve");
  }

  /**
   * The polling fragment stands alone and keeps polling.
   *
   * <p>Asserted explicitly because the failure is silent: a fragment that rendered without its own
   * {@code hx-get} would swap the trigger away on the first refresh and the queue would stop
   * updating, which on a screen showing pending production changes looks exactly like "nothing is
   * waiting".
   */
  @Test
  @DisplayName("returns a queue fragment that carries its own refresh")
  void theFragmentKeepsPolling() throws Exception {
    given(incidents.openIncidents(anyInt())).willReturn(List.of());
    given(incidents.pendingApprovals()).willReturn(List.of());

    mvc.perform(get("/console/queues").with(VIEWER))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("hx-get=\"/console/queues\"")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("hx-trigger=\"every 3s\"")));
  }

  /**
   * An unauthenticated browser is sent to the login page; an unauthenticated script is not.
   *
   * <p>Both halves, because the two mechanisms share a chain and only one of them can answer. A
   * browser that got the Basic challenge would see a credential dialog that cannot say which demo
   * identities exist; a script redirected to a login form would follow it and report a cheerful 200
   * containing HTML.
   */
  @Test
  @DisplayName("sends a browser to the login page and a script a 401")
  void refusesAnUnauthenticatedReaderInTheShapeTheyCanUse() throws Exception {
    mvc.perform(get("/console").accept(MediaType.TEXT_HTML))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                "/login"));

    mvc.perform(get("/console").accept(MediaType.ALL)).andExpect(status().isUnauthorized());
  }

  private static Incident incident() {
    return Incident.open(
        INCIDENT,
        "Checkout p99 latency tripled",
        new ServiceRef("checkout", "demo"),
        Severity.SEV2,
        NOW);
  }

  private static ApprovalRequest approval() {
    ProposedAction action =
        new ProposedAction(
            ActionType.ROLLBACK_DEPLOYMENT,
            new ResourceRef(
                "arn:aws:ecs:eu-west-1:123456789012:service/commander/checkout",
                "123456789012",
                "eu-west-1",
                "demo",
                "ecs:service"),
            Map.of("targetRevision", "checkout:41"),
            "Roll the checkout service back to revision 41.");

    return ApprovalRequest.pending(
        ApprovalId.newId(),
        INCIDENT,
        0L,
        action,
        RiskLevel.MEDIUM,
        "Latency rose within two minutes of revision 42 reaching 100% of tasks.",
        "p99 returns to its pre-deployment baseline within one metric period.",
        List.of(EvidenceId.newId()),
        NOW,
        NOW.plus(Duration.ofHours(1)),
        null);
  }
}
