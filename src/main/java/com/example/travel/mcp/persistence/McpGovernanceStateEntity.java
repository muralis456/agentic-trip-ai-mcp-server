package com.example.travel.mcp.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "mcp_governance_state")
public class McpGovernanceStateEntity {

    @Id
    @Column(name = "tool_name", length = 128, nullable = false)
    private String toolName;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "cooldown_until_epoch_ms")
    private Long cooldownUntilEpochMs;

    @Column(name = "updated_at_epoch_ms", nullable = false)
    private long updatedAtEpochMs;

    protected McpGovernanceStateEntity() {
    }

    public McpGovernanceStateEntity(String toolName, int failureCount,
                                    Long cooldownUntilEpochMs, long updatedAtEpochMs) {
        this.toolName = toolName;
        this.failureCount = failureCount;
        this.cooldownUntilEpochMs = cooldownUntilEpochMs;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    public String getToolName() {
        return toolName;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public Long getCooldownUntilEpochMs() {
        return cooldownUntilEpochMs;
    }

    public long getUpdatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public void setCooldownUntilEpochMs(Long cooldownUntilEpochMs) {
        this.cooldownUntilEpochMs = cooldownUntilEpochMs;
    }

    public void setUpdatedAtEpochMs(long updatedAtEpochMs) {
        this.updatedAtEpochMs = updatedAtEpochMs;
    }
}
