# PhoneBridge Relay HTTP & WebSocket API Reference (v1)

All production requests require TLS 1.2/1.3 (`https://` / `wss://`), ECDSA signed headers (`X-Device-Id`, `X-Timestamp`, `X-Nonce`, `X-Signature`), and `X-Protocol-Version: 1`.

| Endpoint | Method | Auth | Purpose |
| :--- | :--- | :--- | :--- |
| `/v1/health` | `GET` | Public | Returns DB health, protocol version, active WS count |
| `/v1/metrics` | `GET` | Internal/Admin | Privacy-preserving queue/WS/rate-limit counters |
| `/v1/pairing/initiate` | `POST` | Signed ECDSA | Primary starts SPAKE2 session (5m TTL, max 5 attempts) |
| `/v1/pairing/complete` | `POST` | Signed ECDSA | Secondary joins session & submits `pB`, `macB`, `sigB` |
| `/v1/pairing/status/{id}` | `GET` | Bearer + ECDSA | Primary polls Secondary pairing completion |
| `/v1/events` | `POST` | Primary Bearer + ECDSA | Idempotent encrypted envelope submission (`200 OK`) |
| `/v1/events/pending` | `GET` | Secondary Bearer + ECDSA | Bounded pending batch (`cursor`, `limit <= 50`) |
| `/v1/sync` | `POST` | Secondary Bearer + ECDSA | Cursor-based paginated sync with `expired_sequence_floor` |
| `/v1/events/acknowledge` | `POST` | Secondary Bearer + ECDSA | Submits authenticated `AckItem` batch |
| `/v1/devices/revoke` | `POST` | Bearer + ECDSA | Immediately revokes relationship, credentials, & WS sessions |
| `/v1/devices/status` | `GET` | Bearer + ECDSA | Returns relationship status and pending event count |
| `/v1/ws` | `WSS` | Bearer + ECDSA | Realtime `EVENT` push, `RECEIPT` push, `ACK`, `PING`/`PONG` |

## Error Codes
- `401 UNAUTHORIZED_DEVICE`: Invalid token, expired credential, bad signature, or replayed nonce.
- `403 REVOKED_PAIRING`: Device or relationship revoked.
- `410 EXPIRED_EVENT`: Event or pairing session exceeded TTL.
- `413 INVALID_PAYLOAD`: Envelope exceeds 64 KiB payload limit.
- `426 UNSUPPORTED_PROTOCOL`: Protocol version mismatch.
- `429 RATE_LIMITED`: Sliding-window limit exceeded (includes `Retry-After`).
