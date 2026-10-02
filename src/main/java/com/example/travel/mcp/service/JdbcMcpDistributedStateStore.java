package com.example.travel.mcp.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "travel.mcp.distributed-state", name = "enabled", havingValue = "true")
public class JdbcMcpDistributedStateStore implements McpDistributedStateStore {

    private final JdbcTemplate jdbc;

    public JdbcMcpDistributedStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isCircuitOpen(String toolName, long nowEpochMs) {
        Boolean open = jdbc.queryForObject("""
                SELECT COALESCE(cooldown_until_epoch_ms > ?, false)
                FROM mcp_governance_state
                WHERE tool_name = ?
                """, Boolean.class, nowEpochMs, toolName);
        return Boolean.TRUE.equals(open);
    }

    @Override
    public void recordSuccess(String toolName) {
        jdbc.update("""
                INSERT INTO mcp_governance_state(tool_name, failure_count, cooldown_until_epoch_ms, updated_at_epoch_ms)
                VALUES (?, 0, NULL, ?)
                ON CONFLICT (tool_name) DO UPDATE
                SET failure_count = 0,
                    cooldown_until_epoch_ms = NULL,
                    updated_at_epoch_ms = EXCLUDED.updated_at_epoch_ms
                """, toolName, System.currentTimeMillis());
    }

    @Override
    public void recordFailure(String toolName, int failureThreshold, long openMs, long nowEpochMs) {
        jdbc.update("""
                INSERT INTO mcp_governance_state(tool_name, failure_count, cooldown_until_epoch_ms, updated_at_epoch_ms)
                VALUES (?, 1, NULL, ?)
                ON CONFLICT (tool_name) DO UPDATE
                SET failure_count = mcp_governance_state.failure_count + 1,
                    cooldown_until_epoch_ms = CASE
                        WHEN mcp_governance_state.failure_count + 1 >= ?
                        THEN ? + ?
                        ELSE mcp_governance_state.cooldown_until_epoch_ms
                    END,
                    updated_at_epoch_ms = EXCLUDED.updated_at_epoch_ms
                """, toolName, nowEpochMs, failureThreshold, nowEpochMs, openMs);
    }

    @Override
    public boolean allowRequest(String bucketKey, int maxRequests, long windowMs, long nowEpochMs) {
        long windowStart = nowEpochMs - (nowEpochMs % windowMs);
        Integer count = jdbc.queryForObject("""
                INSERT INTO mcp_rate_limit_bucket(bucket_key, window_start_epoch_ms, request_count, updated_at_epoch_ms)
                VALUES (?, ?, 1, ?)
                ON CONFLICT (bucket_key) DO UPDATE
                SET window_start_epoch_ms = CASE
                        WHEN mcp_rate_limit_bucket.window_start_epoch_ms < ?
                        THEN EXCLUDED.window_start_epoch_ms
                        ELSE mcp_rate_limit_bucket.window_start_epoch_ms
                    END,
                    request_count = CASE
                        WHEN mcp_rate_limit_bucket.window_start_epoch_ms < ?
                        THEN 1
                        ELSE mcp_rate_limit_bucket.request_count + 1
                    END,
                    updated_at_epoch_ms = EXCLUDED.updated_at_epoch_ms
                RETURNING request_count
                """, Integer.class, bucketKey, windowStart, nowEpochMs,
                windowStart, windowStart);
        return count != null && count <= maxRequests;
    }
}
