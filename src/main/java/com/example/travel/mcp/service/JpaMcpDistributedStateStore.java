package com.example.travel.mcp.service;

import com.example.travel.mcp.persistence.McpGovernanceStateEntity;
import com.example.travel.mcp.persistence.McpGovernanceStateRepository;
import com.example.travel.mcp.persistence.McpRateLimitBucketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
        prefix = "travel.mcp.distributed-state",
        name = "enabled",
        havingValue = "true")
public class JpaMcpDistributedStateStore implements McpDistributedStateStore {

    private static final Logger log = LoggerFactory.getLogger(JpaMcpDistributedStateStore.class);

    private final McpGovernanceStateRepository governanceRepository;
    private final McpRateLimitBucketRepository rateLimitRepository;

    public JpaMcpDistributedStateStore(
            McpGovernanceStateRepository governanceRepository,
            McpRateLimitBucketRepository rateLimitRepository) {
        this.governanceRepository = governanceRepository;
        this.rateLimitRepository = rateLimitRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCircuitOpen(String toolName, long nowEpochMs) {
        boolean open = governanceRepository.findById(toolName)
                .map(state -> state.getCooldownUntilEpochMs() != null
                        && state.getCooldownUntilEpochMs() > nowEpochMs)
                .orElse(false);

        log.debug("MCP distributed circuit state checked tool={} open={} nowEpochMs={}",
                toolName, open, nowEpochMs);
        return open;
    }

    @Override
    @Transactional
    public void recordSuccess(String toolName) {
        long now = System.currentTimeMillis();
        McpGovernanceStateEntity state = governanceRepository.findById(toolName)
                .orElseGet(() -> new McpGovernanceStateEntity(toolName, 0, null, now));

        state.setFailureCount(0);
        state.setCooldownUntilEpochMs(null);
        state.setUpdatedAtEpochMs(now);
        governanceRepository.save(state);

        log.info("MCP distributed circuit reset tool={} nowEpochMs={}", toolName, now);
    }

    @Override
    @Transactional
    public void recordFailure(String toolName, int failureThreshold,
                              long openMs, long nowEpochMs) {
        governanceRepository.recordFailure(toolName, failureThreshold, openMs, nowEpochMs);

        log.warn("MCP distributed circuit failure recorded tool={} threshold={} openMs={} nowEpochMs={}",
                toolName, failureThreshold, openMs, nowEpochMs);
    }

    @Override
    @Transactional
    public boolean allowRequest(String bucketKey, int maxRequests,
                                long windowMs, long nowEpochMs) {
        long windowStart = nowEpochMs - (nowEpochMs % windowMs);
        Integer count = rateLimitRepository.incrementAndGetCount(
                bucketKey, windowStart, nowEpochMs);

        int requestCount = count == null ? 0 : count;
        boolean allowed = requestCount <= maxRequests;

        log.debug("MCP distributed rate limit checked bucket={} count={} maxRequests={} allowed={}",
                bucketKey, requestCount, maxRequests, allowed);

        return allowed;
    }
}
