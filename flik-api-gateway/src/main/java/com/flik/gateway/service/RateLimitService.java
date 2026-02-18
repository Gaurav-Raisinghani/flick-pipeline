package com.flik.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final StringRedisTemplate redisTemplate;
    private final int requestsPerSecond;

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local current = redis.call('GET', key)
            if current and tonumber(current) >= limit then
                return 0
            end
            current = redis.call('INCR', key)
            if tonumber(current) == 1 then
                redis.call('EXPIRE', key, window)
            end
            return 1
            """;

    private final DefaultRedisScript<Long> rateLimitScript;

    public RateLimitService(StringRedisTemplate redisTemplate,
                            @Value("${rate-limit.requests-per-sec:300}") int requestsPerSecond) {
        this.redisTemplate = redisTemplate;
        this.requestsPerSecond = requestsPerSecond;
        this.rateLimitScript = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
    }

    public boolean isAllowed(String tenantId) {
        try {
            String key = "rate_limit:" + tenantId + ":" + (System.currentTimeMillis() / 1000);
            Long result = redisTemplate.execute(rateLimitScript,
                    List.of(key),
                    String.valueOf(requestsPerSecond),
                    "2");
            return result != null && result == 1;
        } catch (Exception e) {
            log.warn("Rate limit check failed, allowing request: {}", e.getMessage());
            return true;
        }
    }
}
