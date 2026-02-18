package com.flik.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flik.common.dto.TaskRequest;
import com.flik.common.dto.TaskResponse;
import com.flik.gateway.service.CostService;
import com.flik.gateway.service.RateLimitService;
import com.flik.gateway.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.flik.gateway.config.SecurityConfig;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskController.class)
@Import({SecurityConfig.class, TaskControllerTest.MetricsConfig.class})
class TaskControllerTest {

    @TestConfiguration
    static class MetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TaskService taskService;

    @MockBean
    private RateLimitService rateLimitService;

    @MockBean
    private CostService costService;

    private static final String AUTH_HEADER = "Bearer test-token";

    @Test
    void submitTask_returns202_whenValid() throws Exception {
        when(rateLimitService.isAllowed(any())).thenReturn(true);

        TaskResponse resp = new TaskResponse();
        resp.setTaskId(UUID.randomUUID());
        resp.setStatus("QUEUED");
        resp.setTaskType("TEXT");
        resp.setCreatedAt(Instant.now());
        when(taskService.submitTask(any())).thenReturn(resp);

        String body = """
                {"tenantId":"tenant-1","taskType":"TEXT","priority":0,"payload":{"prompt":"hello"}}
                """;

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.taskType").value("TEXT"));
    }

    @Test
    void submitTask_returns400_whenMissingFields() throws Exception {
        String body = """
                {"priority":0}
                """;

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void submitTask_returns429_whenRateLimited() throws Exception {
        when(rateLimitService.isAllowed(any())).thenReturn(false);

        String body = """
                {"tenantId":"tenant-1","taskType":"TEXT","priority":0}
                """;

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void submitTask_returns401_whenNoAuthHeader() throws Exception {
        String body = """
                {"tenantId":"tenant-1","taskType":"TEXT","priority":0}
                """;

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTask_returns200_whenFound() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskResponse resp = new TaskResponse();
        resp.setTaskId(taskId);
        resp.setStatus("COMPLETED");
        resp.setTaskType("IMAGE");
        when(taskService.getTask(taskId)).thenReturn(Optional.of(resp));

        mockMvc.perform(get("/api/v1/tasks/" + taskId)
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getTask_returns404_whenNotFound() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(taskService.getTask(taskId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/tasks/" + taskId)
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isNotFound());
    }
}
