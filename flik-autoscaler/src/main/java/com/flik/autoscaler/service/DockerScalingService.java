package com.flik.autoscaler.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DockerScalingService {

    private static final Logger log = LoggerFactory.getLogger(DockerScalingService.class);

    private final Map<String, Integer> currentReplicas = new ConcurrentHashMap<>(Map.of(
            "worker-text", 2,
            "worker-image", 2,
            "worker-video", 1
    ));

    public int getCurrentReplicas(String serviceName) {
        return currentReplicas.getOrDefault(serviceName, 1);
    }

    public void scaleTo(String serviceName, int replicas) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "compose", "up", "-d", "--scale",
                    serviceName + "=" + replicas, "--no-recreate", serviceName);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("docker compose: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                currentReplicas.put(serviceName, replicas);
                log.info("Scaled {} to {} replicas", serviceName, replicas);
            } else {
                log.error("Failed to scale {} to {} replicas, exit code: {}", serviceName, replicas, exitCode);
            }
        } catch (Exception e) {
            log.error("Failed to execute docker scale command for {}: {}", serviceName, e.getMessage());
        }
    }
}
