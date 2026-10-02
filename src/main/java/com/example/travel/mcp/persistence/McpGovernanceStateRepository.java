package com.example.travel.mcp.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface McpGovernanceStateRepository
        extends JpaRepository<McpGovernanceStateEntity, String> {

    @Modifying
    @Query(value = """
            INSERT INTO mcp_governance_state(
                tool_name, failure_count, cooldown_until_epoch_ms, updated_at_epoch_ms)
            VALUES (:toolName, 1, NULL, :nowEpochMs)
            ON CONFLICT (tool_name) DO UPDATE
            SET failure_count = mcp_governance_state.failure_count + 1,
                cooldown_until_epoch_ms = CASE
                    WHEN mcp_governance_state.failure_count + 1 >= :failureThreshold
                    THEN :nowEpochMs + :openMs
                    ELSE mcp_governance_state.cooldown_until_epoch_ms
                END,
                updated_at_epoch_ms = :nowEpochMs
            """, nativeQuery = true)
    int recordFailure(@Param("toolName") String toolName,
                      @Param("failureThreshold") int failureThreshold,
                      @Param("openMs") long openMs,
                      @Param("nowEpochMs") long nowEpochMs);
}
