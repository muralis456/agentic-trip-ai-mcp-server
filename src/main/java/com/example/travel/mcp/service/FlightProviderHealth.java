package com.example.travel.mcp.service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight in-process circuit breaker; avoids repeatedly hammering a failing provider. */
final class FlightProviderHealth {
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong(0L);
    private final int failureThreshold;
    private final long cooldownMillis;

    FlightProviderHealth(int failureThreshold, long cooldownMillis) {
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
    }

    boolean allowRequest() {
        long until = openUntil.get();
        if (until == 0L) return true;
        if (System.currentTimeMillis() >= until) {
            return openUntil.compareAndSet(until, 0L);
        }
        return false;
    }

    void success() {
        consecutiveFailures.set(0);
        openUntil.set(0L);
    }

    void failure(boolean countTowardCircuit) {
        if (!countTowardCircuit) return;
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openUntil.set(System.currentTimeMillis() + cooldownMillis);
        }
    }

    int failures() { return consecutiveFailures.get(); }
}
