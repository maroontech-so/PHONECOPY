# PhoneBridge Architecture

## 1. System Overview
PhoneBridge is a private, one-way, end-to-end encrypted event-delivery bridge between two personally owned Android devices:
- **Primary (Tecno)**: Captures selected notifications and reminders, encrypts them locally, persists them in a transactional Room outbox, and delivers them via HTTPS and WebSocket (WSS) through the Relay.
- **Relay Server (Ktor + PostgreSQL)**: Zero-knowledge durable store-and-forward relay. Authenticates devices, persists encrypted envelopes, pushes realtime frames over WSS, serves paginated HTTPS sync, tracks authenticated delivery receipts, and purges expired ciphertexts.
- **Secondary (Samsung)**: Receives encrypted envelopes over WSS or HTTPS sync fallback, verifies AAD & AEAD tags, deduplicates into a transactional Room inbox, advances contiguous sync cursors, presents local notifications and Android Calendar events, and sends authenticated receipts.

## 2. One-Way Control Invariant
The Primary device **never** accepts operational commands from the Secondary or Relay. The Relay rejects `POST /v1/events` from any `SECONDARY` device (`HTTP 403`). Secondary devices may only transmit protocol acknowledgements (`ACK`), sync cursor queries (`POST /v1/sync`), and revocation requests (`POST /v1/devices/revoke`).

## 3. Delivery Guarantees & Android Realities
- **Model**: At-least-once network transport with idempotent deduplication across Primary (`sourceDedupKey` + `(relationshipId, sequenceNumber)`), Relay (`PRIMARY KEY (relationship_id, event_id)` + `UNIQUE (relationship_id, sequence_number)`), and Secondary (`eventId` PK + `OnConflictStrategy.IGNORE`).
- **Dual-Path Delivery**: WSS provides low-latency push when foreground/network allows; HTTPS `POST /v1/sync` + `WorkManager` (`OutboxFlushWorker`, `InboxSyncWorker`) + `ConnectivityManager.NetworkCallback` + `BOOT_COMPLETED` ensure recovery after Doze, process kill, reboot, or server restart.
- **Honest Limitations**: Under Android OEM battery killers (Tecno HiOS / Samsung One UI) or force-stop state, real-time delivery cannot be guaranteed until the OS lifts restrictions or the app is launched. Events remain durably queued in Room until delivered or expired.
