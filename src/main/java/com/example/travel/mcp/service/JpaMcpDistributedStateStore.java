package com.example.travel.mcp.service;

import com.example.travel.mcp.persistence.McpGovernanceStateEntity;
import com.example.travel.mcp.persistence.McpGovernanceStateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
        prefix = "travel.mcp.distributed-state",
        name = "enabled",
        havingValue = "true")
public class JpaMcpDistributedStateStore implements McpDistributedStateStore {

    private final McpGovernanceStateRepository governanceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public JpaMcpDistributedStateStore(McpGovernanceStateRepository governanceRepository) {
        this.governanceRepository = governanceRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCircuitOpen(String toolName, long nowEpochMs) {
        return governanceRepository.findById(toolName)
                .map(state -> state.getCooldownUntilEpochMs() != null
                        && state.getCooldownUntilEpochMs() > nowEpochMs)
                .orElse(false);
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
    }

    @Override
    @Transactional
    public void recordFailure(String toolName, int failureThreshold,
                              long openMs, long nowEpochMs) {
        entityManager.createNativeQuery("""
                INSERT INTO mcp_governance_state(
                    tool_name, failure_count, cooldown_until_epoch_ms, updated_at_epoch_ms)
                VALUES (
                    :toolName, 1, NULL, :nowEpochMs)
                ON CONFLICT (tool_name) DO UPDATE
                SET failure_count = mcp_governance_state.failure_count + 1,
                    cooldown_until_epoch_ms = CASE
                        WHEN mcp_governance_state.failure_count + 1 >= :failureThreshold
                        THEN :nowEpochMs + :openMs
                        ELSE mcp_governance_state.cooldown_until_epoch_ms
                    END,
                    updated_at_epoch_ms = :nowEpochMs
                """)
                .setParameter("toolName", toolName)
                .setParameter("nowEpochMs", nowEpochMs)
                .setParameter("failureThreshold", failureThreshold)
                .setParameter("openMs", openMs)
                .executeUpdate();
    }

    @Override
    @Transactional
    public boolean allowRequest(String bucketKey, int maxRequests,
                                long windowMs, long nowEpochMs) {
        long windowStart = nowEpochMs - (nowEpochMs % windowMs);

        Object result = entityManager.createNativeQuery("""
                WITH upsert AS (
                    INSERT INTO mcp_rate_limit_bucket(
                        bucket_key, window_start_epoch_ms, request_count, updated_at_epoch_ms)
                    VALUES (:bucketKey, :windowStart, 1, :nowEpochMs)
                    ON CONFLICT (bucket_key) DO UPDATE
                    SET window_start_epoch_ms = CASE
                            WHEN mcp_rate_limit_bucket.window_start_epoch_ms < :windowStart
                            THEN EXCLUDED.window_start_epoch_ms
                            ELSE mcp_rate_limit_bucket.window_start_epoch_ms
                        END,
                        request_count = CASE
                            WHEN mcp_rate_limit_bucket.window_start_epoch_ms < :windowStart
                            THEN 1
                            ELSE mcp_rate_limit_bucket.request_count + 1
                        END,
                        updated_at_epoch_ms = EXCLUDED.updated_at_epoch_ms
                    RETURNING request_count
                )
                SELECT request_count FROM upsert
                """)
                .setParameter("bucketKey", bucketKey)
                .setParameter("windowStart", windowStart)
                .setParameter("nowEpochMs", nowEpochMs)
                .getSingleResult();

        int count = ((Number) result).intValue();
        return count <= maxRequests;
    }
}
