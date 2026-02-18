package com.flik.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.flik.gateway.config.SecurityConfig;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSource dataSource;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Test
    void health_returns200_whenAllUp() throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(conn);
        when(rabbitTemplate.execute(any())).thenReturn(null);

        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection redisConn = mock(RedisConnection.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(redisConn);
        when(redisConn.ping()).thenReturn("PONG");

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.postgresql").value("UP"))
                .andExpect(jsonPath("$.components.rabbitmq").value("UP"))
                .andExpect(jsonPath("$.components.redis").value("UP"));
    }

    @Test
    void health_returns503_whenDatabaseDown() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("Connection refused"));
        when(rabbitTemplate.execute(any())).thenReturn(null);

        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection redisConn = mock(RedisConnection.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(redisConn);
        when(redisConn.ping()).thenReturn("PONG");

        mockMvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.components.postgresql").value("DOWN"));
    }

    @Test
    void health_isAccessibleWithoutAuth() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("skip"));
        when(rabbitTemplate.execute(any())).thenThrow(new RuntimeException("skip"));
        when(redisTemplate.getConnectionFactory()).thenThrow(new RuntimeException("skip"));

        mockMvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable());
    }
}
