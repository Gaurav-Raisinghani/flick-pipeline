package com.flik.autoscaler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class QueueMonitorService {

    private static final Logger log = LoggerFactory.getLogger(QueueMonitorService.class);

    private final String managementUrl;
    private final String authHeader;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, AtomicLong> queueDepths = new ConcurrentHashMap<>();

    public QueueMonitorService(
            @Value("${rabbitmq.management-url:http://rabbitmq:15672}") String managementUrl,
            @Value("${rabbitmq.username:flik}") String username,
            @Value("${rabbitmq.password:flik}") String password,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.managementUrl = managementUrl;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = objectMapper;

        for (String queue : new String[]{"flik.tasks.p0", "flik.tasks.p1", "flik.tasks.p2"}) {
            AtomicLong depth = new AtomicLong(0);
            queueDepths.put(queue, depth);
            Gauge.builder("flik_queue_depth", depth, AtomicLong::get)
                    .tag("queue", queue)
                    .register(meterRegistry);
        }
    }

    public long getQueueDepth(String queueName) {
        try {
            String encodedVhost = "%2F";
            String url = managementUrl + "/api/queues/" + encodedVhost + "/" + queueName;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                long depth = node.path("messages").asLong(0);
                AtomicLong counter = queueDepths.get(queueName);
                if (counter != null) {
                    counter.set(depth);
                }
                return depth;
            }
        } catch (Exception e) {
            log.warn("Failed to get queue depth for {}: {}", queueName, e.getMessage());
        }
        return -1;
    }

    public long getTotalDepth() {
        long p0 = getQueueDepth("flik.tasks.p0");
        long p1 = getQueueDepth("flik.tasks.p1");
        long p2 = getQueueDepth("flik.tasks.p2");
        return Math.max(0, p0) + Math.max(0, p1) + Math.max(0, p2);
    }
}
