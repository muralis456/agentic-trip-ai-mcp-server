package com.example.travel.mcp.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface McpRateLimitBucketRepository
        extends JpaRepository<McpRateLimitBucketEntity, String> {

    @Query(value = """
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
            """, nativeQuery = true)
    Integer incrementAndGetCount(@Param("bucketKey") String bucketKey,
                                 @Param("windowStart") long windowStart,
                                 @Param("nowEpochMs") long nowEpochMs);
}
