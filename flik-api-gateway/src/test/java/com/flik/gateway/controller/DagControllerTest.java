package com.flik.gateway.controller;

import com.flik.common.dto.DagResponse;
import com.flik.common.dto.TaskResponse;
import com.flik.gateway.service.CostService;
import com.flik.gateway.service.DagService;
import com.flik.gateway.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.flik.gateway.config.SecurityConfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DagController.class)
@Import(SecurityConfig.class)
class DagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DagService dagService;

    @MockBean
    private RateLimitService rateLimitService;

    @MockBean
    private CostService costService;

    private static final String AUTH_HEADER = "Bearer test-token";

    @Test
    void submitDag_returns202_whenValid() throws Exception {
        when(rateLimitService.isAllowed(any())).thenReturn(true);

        UUID dagId = UUID.randomUUID();
        DagResponse resp = new DagResponse();
        resp.setDagId(dagId);
        resp.setStatus("RUNNING");
        resp.setTasks(List.of());
        when(dagService.submitDag(any())).thenReturn(resp);

        String body = """
                {
                  "tenantId": "tenant-1",
                  "priority": 0,
                  "steps": [
                    {"taskType": "TEXT", "payload": {"prompt": "hello"}},
                    {"taskType": "IMAGE", "payload": {"action": "upscale"}}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/dags")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.dagId").value(dagId.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void submitDag_returns400_whenNoSteps() throws Exception {
        String body = """
                {"tenantId": "tenant-1", "priority": 0, "steps": []}
                """;

        mockMvc.perform(post("/api/v1/dags")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getDag_returns200_whenFound() throws Exception {
        UUID dagId = UUID.randomUUID();
        DagResponse resp = new DagResponse();
        resp.setDagId(dagId);
        resp.setStatus("COMPLETED");
        resp.setTasks(List.of());
        when(dagService.getDag(dagId)).thenReturn(Optional.of(resp));

        mockMvc.perform(get("/api/v1/dags/" + dagId)
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getDag_returns404_whenNotFound() throws Exception {
        UUID dagId = UUID.randomUUID();
        when(dagService.getDag(dagId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/dags/" + dagId)
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isNotFound());
    }
}
