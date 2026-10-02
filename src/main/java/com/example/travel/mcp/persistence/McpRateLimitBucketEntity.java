package com.example.travel.mcp.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "mcp_rate_limit_bucket")
public class McpRateLimitBucketEntity {

    @Id
    @Column(name = "bucket_key", length = 255, nullable = false)
    private String bucketKey;

    @Column(name = "window_start_epoch_ms", nullable = false)
    private long windowStartEpochMs;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "updated_at_epoch_ms", nullable = false)
    private long updatedAtEpochMs;

    protected McpRateLimitBucketEntity() {
    }

    public McpRateLimitBucketEntity(String bucketKey, long windowStartEpochMs,
                                    int requestCount, long updatedAtEpochMs) {
        this.bucketKey = bucketKey;
        this.windowStartEpochMs = windowStartEpochMs;
        this.requestCount = requestCount;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    public String getBucketKey() {
        return bucketKey;
    }

    public long getWindowStartEpochMs() {
        return windowStartEpochMs;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public long getUpdatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    public void setWindowStartEpochMs(long windowStartEpochMs) {
        this.windowStartEpochMs = windowStartEpochMs;
    }

    public void setRequestCount(int requestCount) {
        this.requestCount = requestCount;
    }

    public void setUpdatedAtEpochMs(long updatedAtEpochMs) {
        this.updatedAtEpochMs = updatedAtEpochMs;
    }
}
