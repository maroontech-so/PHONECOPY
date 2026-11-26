# PhoneBridge Protocol Specification (v1)

## 1. Device Identity
- Each device generates a hardware-backed **ECDSA P-256 (`secp256r1`)** keypair in `AndroidKeyStore`.
- `device_id = "dev_" + hex(SHA-256(SPKI_PublicKey)[0..16])`.
- All authenticated HTTP and WebSocket upgrades carry `X-Protocol-Version`, `X-Device-Id`, `X-Timestamp` (±300s window), `X-Nonce` (single-use UUID stored in `auth_nonces`), and `X-Signature = ECDSA_SHA256(METHOD|PATH|TIMESTAMP|NONCE|SHA256(BODY))`, plus `Authorization: Bearer <token>`.

## 2. Cryptographic Pairing (RFC 9382 SPAKE2-P256)
- The 6-digit pairing PIN is **never** used as an encryption key.
- Primary generates `pairing_session_id` + 6-digit PIN `pw`, stretches `pw` with PBKDF2-HMAC-SHA256 (`10,000` rounds, session-bound salt) into scalar `w`, computes `pA = x*G + w*M`, and initiates `POST /v1/pairing/initiate`.
- Secondary computes `pB = y*G + w*N`, derives `Z = h*y*(pA - w*M)`, derives `K_root (256-bit)`, `K_confirm_A`, `K_confirm_B` via HKDF-SHA256 over the full transcript `TT`, signs `SHA-256(TT)` with its ECDSA identity key, and submits `POST /v1/pairing/complete`.
- Primary polls `GET /v1/pairing/status/{sessionId}`, computes `Z = h*x*(pB - w*N)`, and verifies both `macB` (constant-time) and Secondary's ECDSA signature before marking the relationship `ACTIVE`.
- Both devices wrap `K_root` and their bearer token using an `AndroidKeyStore` AES-256-GCM master wrapping key.

## 3. AEAD Event Envelope & Nonce Management
- Subkeys derived via HKDF-SHA256:
  - `K_enc = HKDF-Expand(K_root, "PhoneBridge-v1-Enc-P2S|" + relationship_id + "|" + key_epoch)`
  - `K_ack = HKDF-Expand(K_root, "PhoneBridge-v1-Ack-S2P|" + relationship_id + "|" + key_epoch)`
- **Deterministic Unique Nonce (96-bit)**: `key_epoch (4B big-endian) || sequence_number (8B big-endian)`. Guaranteed never to repeat across process restarts.
- **Cipher**: AES-256-GCM (128-bit tag).
- **AAD**: `PBv1|protocol_version|event_id|relationship_id|sequence_number|key_epoch|created_at|expires_at|payload_type`.

## 4. Delivery State Machine
`CAPTURED -> PENDING -> SENDING -> SERVER_ACCEPTED -> DEVICE_RECEIVED -> READ`
Transient failures transition `SENDING -> RETRY_WAIT -> SENDING` via exponential backoff (`2s, 5s, 15s, 30s, 60s, 2m, 5m, 15m`) + ±20% jitter. Expired items transition to `EXPIRED`. Non-retryable errors transition to `PERMANENT_FAILURE`. `SERVER_ACCEPTED` is never treated as recipient delivery.

## 5. Synchronization Cursor & Gap Detection
Secondary tracks `(lastContiguousSeq, highestSeenSeq, missingSequences)`. `lastContiguousSeq` advances **only** when `arrivedSeq == lastContiguousSeq + 1` (or when `missingSequences` closes a gap), ensuring interrupted sync or out-of-order delivery never skips an event. If events expire on the Relay, `expired_sequence_floor` unblocks the cursor safely.
