CREATE TABLE IF NOT EXISTS mcp_governance_state (
    tool_name VARCHAR(128) PRIMARY KEY,
    failure_count INTEGER NOT NULL DEFAULT 0,
    cooldown_until_epoch_ms BIGINT NULL,
    updated_at_epoch_ms BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS mcp_rate_limit_bucket (
    bucket_key VARCHAR(255) PRIMARY KEY,
    window_start_epoch_ms BIGINT NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0,
    updated_at_epoch_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mcp_rate_limit_updated
    ON mcp_rate_limit_bucket (updated_at_epoch_ms);