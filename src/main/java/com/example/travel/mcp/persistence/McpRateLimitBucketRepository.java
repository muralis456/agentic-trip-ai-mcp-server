package com.example.travel.mcp.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McpRateLimitBucketRepository
        extends JpaRepository<McpRateLimitBucketEntity, String> {
}
