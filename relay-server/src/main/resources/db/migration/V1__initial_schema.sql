-- PhoneBridge Relay Server Initial PostgreSQL Schema (Migration V1)
-- Strictly zero plaintext notification or calendar content stored.

CREATE TABLE IF NOT EXISTS devices (
    device_id VARCHAR(128) PRIMARY KEY,
    device_name VARCHAR(128) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('PRIMARY', 'SECONDARY')),
    identity_public_key TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    revoked_at BIGINT
);

CREATE TABLE IF NOT EXISTS device_relationships (
    relationship_id VARCHAR(128) PRIMARY KEY,
    primary_device_id VARCHAR(128) NOT NULL REFERENCES devices(device_id),
    secondary_device_id VARCHAR(128) NOT NULL REFERENCES devices(device_id),
    key_epoch INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at BIGINT NOT NULL,
    revoked_at BIGINT,
    CONSTRAINT uq_relationship_pair UNIQUE (primary_device_id, secondary_device_id)
);

CREATE TABLE IF NOT EXISTS device_credentials (
    credential_id VARCHAR(128) PRIMARY KEY,
    device_id VARCHAR(128) NOT NULL REFERENCES devices(device_id),
    relationship_id VARCHAR(128),
    token_hash_sha256 CHAR(64) NOT NULL UNIQUE,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_credentials_device ON device_credentials(device_id, revoked_at);

CREATE TABLE IF NOT EXISTS pairing_sessions (
    pairing_session_id VARCHAR(64) PRIMARY KEY,
    primary_device_id VARCHAR(128) NOT NULL,
    primary_device_name VARCHAR(128) NOT NULL,
    primary_identity_public_key TEXT NOT NULL,
    spake2_message_a TEXT NOT NULL,
    spake2_message_b TEXT,
    key_confirmation_mac_b TEXT,
    identity_signature_b TEXT,
    secondary_device_id VARCHAR(128),
    secondary_device_name VARCHAR(128),
    secondary_identity_public_key TEXT,
    relationship_id VARCHAR(128),
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pairing_expires ON pairing_sessions(expires_at);

CREATE TABLE IF NOT EXISTS encrypted_events (
    event_id VARCHAR(128) NOT NULL,
    relationship_id VARCHAR(128) NOT NULL REFERENCES device_relationships(relationship_id),
    source_device_id VARCHAR(128) NOT NULL,
    destination_device_id VARCHAR(128) NOT NULL,
    sequence_number BIGINT NOT NULL,
    protocol_version INT NOT NULL DEFAULT 1,
    key_epoch INT NOT NULL DEFAULT 1,
    payload_type VARCHAR(32) NOT NULL,
    ciphertext TEXT NOT NULL,
    nonce TEXT NOT NULL,
    auth_tag TEXT NOT NULL,
    ciphertext_hash CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    accepted_at BIGINT NOT NULL,
    recipient_received_at BIGINT,
    status VARCHAR(32) NOT NULL CHECK (status IN ('SERVER_ACCEPTED', 'DEVICE_RECEIVED', 'EXPIRED')),
    PRIMARY KEY (relationship_id, event_id),
    CONSTRAINT uq_relationship_sequence UNIQUE (relationship_id, sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_events_pending_sync
    ON encrypted_events(relationship_id, status, sequence_number);

CREATE INDEX IF NOT EXISTS idx_events_expiration
    ON encrypted_events(expires_at);

CREATE TABLE IF NOT EXISTS delivery_receipts (
    receipt_id VARCHAR(128) PRIMARY KEY,
    relationship_id VARCHAR(128) NOT NULL REFERENCES device_relationships(relationship_id),
    event_id VARCHAR(128) NOT NULL,
    sequence_number BIGINT NOT NULL,
    ack_mac TEXT NOT NULL,
    received_at BIGINT NOT NULL,
    recorded_at BIGINT NOT NULL,
    CONSTRAINT uq_receipt_event UNIQUE (relationship_id, event_id)
);

CREATE TABLE IF NOT EXISTS sync_metadata (
    relationship_id VARCHAR(128) PRIMARY KEY REFERENCES device_relationships(relationship_id),
    highest_accepted_sequence BIGINT NOT NULL DEFAULT 0,
    expired_sequence_floor BIGINT NOT NULL DEFAULT 0,
    last_sync_at BIGINT
);

CREATE TABLE IF NOT EXISTS device_revocations (
    revocation_id VARCHAR(128) PRIMARY KEY,
    relationship_id VARCHAR(128) NOT NULL,
    revoked_device_id VARCHAR(128) NOT NULL,
    initiated_by_device_id VARCHAR(128) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    revoked_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_nonces (
    nonce VARCHAR(128) PRIMARY KEY,
    device_id VARCHAR(128) NOT NULL,
    expires_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_auth_nonces_exp ON auth_nonces(expires_at);
