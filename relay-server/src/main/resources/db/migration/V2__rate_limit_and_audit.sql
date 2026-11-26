-- PhoneBridge Relay Server Migration V2: Privacy-preserving operational counters

CREATE TABLE IF NOT EXISTS relay_metrics_snapshots (
    snapshot_id VARCHAR(64) PRIMARY KEY,
    expired_events_purged BIGINT NOT NULL DEFAULT 0,
    expired_nonces_purged BIGINT NOT NULL DEFAULT 0,
    rate_limit_rejections BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL
);
