package com.example.travel.mcp.service;

public interface McpDistributedStateStore {

    boolean isCircuitOpen(String toolName, long nowEpochMs);

    void recordSuccess(String toolName);

    void recordFailure(String toolName, int failureThreshold, long openMs, long nowEpochMs);

    boolean allowRequest(String bucketKey, int maxRequests, long windowMs, long nowEpochMs);
}
