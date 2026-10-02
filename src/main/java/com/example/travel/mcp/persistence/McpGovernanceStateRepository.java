package com.example.travel.mcp.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McpGovernanceStateRepository
        extends JpaRepository<McpGovernanceStateEntity, String> {
}
