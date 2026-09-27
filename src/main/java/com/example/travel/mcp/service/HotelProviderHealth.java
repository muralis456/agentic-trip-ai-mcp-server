package com.example.travel.mcp.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HotelProviderHealth {
    private final int failureThreshold;
    private final Duration cooldown;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Instant openUntil = Instant.EPOCH;

    public HotelProviderHealth(
            @Value("${travel.hotels.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${travel.hotels.circuit-breaker.cooldown:60s}") Duration cooldown) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldown = cooldown == null ? Duration.ofSeconds(60) : cooldown;
    }

    public boolean allowRequest() {
        Instant until = openUntil;
        if (Instant.now().isBefore(until)) return false;
        return true;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil = Instant.EPOCH;
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntil = Instant.now().plus(cooldown);
        }
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
