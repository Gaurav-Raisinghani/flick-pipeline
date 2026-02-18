package com.flik.gateway.controller;

import com.flik.gateway.service.CostService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import com.flik.gateway.config.SecurityConfig;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CostController.class)
@Import(SecurityConfig.class)
class CostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CostService costService;

    private static final String AUTH_HEADER = "Bearer test-token";

    @Test
    void getCosts_returns200_withSummary() throws Exception {
        Map<String, Object> summary = Map.of(
                "totalCost", 12.45,
                "costPerType", Map.of("TEXT", 0.001, "IMAGE", 0.01, "VIDEO", 0.10)
        );
        when(costService.getCostSummary()).thenReturn(summary);

        mockMvc.perform(get("/api/v1/costs")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCost").value(12.45))
                .andExpect(jsonPath("$.costPerType.TEXT").value(0.001));
    }

    @Test
    void getCosts_returns401_withoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/costs"))
                .andExpect(status().isUnauthorized());
    }
}
