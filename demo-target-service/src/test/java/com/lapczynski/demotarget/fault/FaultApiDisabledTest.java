package com.lapczynski.demotarget.fault;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The default posture: fault injection off.
 *
 * <p>Runs with no property override, so it exercises exactly what an unconfigured deployment does.
 * Together with {@link FaultApiTest} this proves the switch gates behaviour rather than merely
 * existing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FaultApiDisabledTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("injection is refused by default, with an explanation")
  void injectionRefusedByDefault() throws Exception {
    mockMvc
        .perform(
            post("/admin/faults/latency")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationSeconds\": 60}"))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.error")
                .value(org.hamcrest.Matchers.containsString("demo.faults.enabled=true")));
  }

  @Test
  @DisplayName("status reports that injection is disabled")
  void statusReportsDisabled() throws Exception {
    mockMvc
        .perform(get("/admin/faults"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.injectionEnabled").value(false))
        .andExpect(jsonPath("$.active").isEmpty());
  }

  @Test
  @DisplayName("reset still works when injection is disabled")
  void resetStillWorks() throws Exception {
    mockMvc.perform(delete("/admin/faults")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("the business API is unaffected")
  void businessApiWorks() throws Exception {
    mockMvc
        .perform(
            post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"o-42\",\"amountMinor\":2500,\"currency\":\"EUR\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("AUTHORIZED"))
        .andExpect(jsonPath("$.orderId").value("o-42"));
  }
}
