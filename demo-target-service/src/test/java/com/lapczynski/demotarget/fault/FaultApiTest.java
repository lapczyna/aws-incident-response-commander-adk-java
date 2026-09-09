package com.lapczynski.demotarget.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The fault control API end to end.
 *
 * <p>Injection is enabled for this test class only. The default configuration has it off, and
 * {@link FaultApiDisabledTest} covers that path — the two together prove the switch actually gates
 * behaviour rather than merely existing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "demo.faults.enabled=true")
class FaultApiTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private FaultRegistry registry;

  @AfterEach
  void clearFaults() {
    registry.clearAll();
  }

  @Test
  @DisplayName("a fault can be injected, observed and cleared")
  void injectObserveClear() throws Exception {
    mockMvc
        .perform(
            post("/admin/faults/latency")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"durationSeconds": 60, "parameters": {"millis": "250"},
                     "activatedBy": "demo-script"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("LATENCY"))
        .andExpect(jsonPath("$.parameters.millis").value("250"))
        .andExpect(jsonPath("$.activatedBy").value("demo-script"));

    mockMvc
        .perform(get("/admin/faults"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.injectionEnabled").value(true))
        .andExpect(jsonPath("$.active[0].type").value("LATENCY"))
        .andExpect(jsonPath("$.active[0].remainingSeconds").isNumber());

    mockMvc.perform(delete("/admin/faults/latency")).andExpect(status().isNoContent());
    mockMvc.perform(get("/admin/faults")).andExpect(jsonPath("$.active").isEmpty());
  }

  @Test
  @DisplayName("an out-of-range magnitude is clamped and the clamped value is reported back")
  void magnitudeIsClampedAndReported() throws Exception {
    mockMvc
        .perform(
            post("/admin/faults/latency")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationSeconds\": 60, \"parameters\": {\"millis\": \"999999\"}}"))
        .andExpect(status().isCreated())
        .andExpect(
            jsonPath("$.parameters.millis")
                .value(String.valueOf(FaultType.MAX_LATENCY.toMillis())));
  }

  @Test
  @DisplayName("a duration beyond the API bound is rejected at the edge")
  void oversizedDurationRejected() throws Exception {
    mockMvc
        .perform(
            post("/admin/faults/latency")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationSeconds\": 999999}"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("an unknown fault type is a bad request that lists the valid ones")
  void unknownTypeIsHelpful() throws Exception {
    mockMvc
        .perform(
            post("/admin/faults/delete-everything")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationSeconds\": 60}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("LATENCY")));
  }

  @Test
  @DisplayName("reset clears everything at once")
  void resetClearsEverything() throws Exception {
    registry.inject(FaultType.LATENCY, java.util.Map.of(), java.time.Duration.ofMinutes(1), "t");
    registry.inject(FaultType.ERROR_RATE, java.util.Map.of(), java.time.Duration.ofMinutes(1), "t");

    mockMvc
        .perform(delete("/admin/faults"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cleared").value(2));

    assertThat(registry.activeFaults()).isEmpty();
  }

  @Test
  @DisplayName("an injected endpoint failure hits its target and spares everything else")
  void endpointFailureIsScoped() throws Exception {
    registry.inject(
        FaultType.ENDPOINT_FAILURE,
        java.util.Map.of("path", "/api/payments", "status", "503"),
        java.time.Duration.ofMinutes(1),
        "test");

    mockMvc
        .perform(
            post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"o-1\",\"amountMinor\":1500,\"currency\":\"EUR\"}"))
        .andExpect(status().isServiceUnavailable());

    mockMvc.perform(get("/api/version")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("the control surface keeps working while a total-failure fault is active")
  void controlSurfaceStaysReachable() throws Exception {
    registry.inject(
        FaultType.ERROR_RATE,
        java.util.Map.of("rate", "1.0"),
        java.time.Duration.ofMinutes(1),
        "test");

    // Every business request now fails...
    mockMvc.perform(get("/api/version")).andExpect(status().isInternalServerError());

    // ...but the way out is still open. Without this exemption a fault would be self-perpetuating.
    mockMvc.perform(get("/admin/faults")).andExpect(status().isOk());
    mockMvc.perform(delete("/admin/faults")).andExpect(status().isOk());

    mockMvc.perform(get("/api/version")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("a bad version is reported as simulated, never as genuine")
  void badVersionIsMarkedSimulated() throws Exception {
    registry.inject(
        FaultType.BAD_VERSION,
        java.util.Map.of("version", "2.4.0-bad"),
        java.time.Duration.ofMinutes(1),
        "test");

    mockMvc
        .perform(get("/api/version"))
        .andExpect(jsonPath("$.version").value("2.4.0-bad"))
        .andExpect(jsonPath("$.simulated").value(true));
  }
}
