# PhoneBridge Security Threat Model & Privacy Limitations

## 1. Threat Model & Mitigations
| Threat | Mitigation |
| :--- | :--- |
| **Compromised / Curious Relay Server** | Payloads encrypted end-to-end with AES-256-GCM; Relay never receives `K_root` or 6-digit pairing PIN. |
| **Offline Brute Force of 6-Digit Code** | RFC 9382 SPAKE2 prevents offline dictionary verification; Relay enforces 5-minute TTL, 5 max attempts, and IP rate limits. |
| **Man-in-the-Middle (MITM)** | TLS 1.2/1.3 strict certificate validation + SPAKE2 transcript bound to ECDSA P-256 identity signatures. |
| **Replay Attacks** | HTTP requests require single-use `X-Nonce` + ±5m `X-Timestamp`; AEAD binds `(relationship_id, sequence_number, key_epoch)` in AAD; DB enforces unique `(relationship_id, event_id)`. |
| **Unauthorized Device / Role Escalation** | Relay enforces `PRIMARY` vs `SECONDARY` role per endpoint; Secondary cannot submit events or command Primary. |
| **Nonce Reuse** | 96-bit nonce is deterministically `(key_epoch || monotonic_sequence_number)` allocated transactionally in Room. |
| **Local Device Extraction** | `K_root` and bearer tokens are wrapped with `AndroidKeyStore` AES-256-GCM master key; `allowBackup="false"` set in manifest. |

## 2. Secure Deletion Limitations
While PhoneBridge executes `DELETE` statements on expired ciphertexts in PostgreSQL and Room and sets `allowBackup="false"`, true cryptographic erasure on physical storage has known physical-layer limits:
- **SSDs / Flash Wear-Leveling**: NAND flash controllers remap blocks during wear-leveling; deleted SQLite/PostgreSQL pages may persist until TRIM/GC. Wrapping local secrets with `AndroidKeyStore` mitigates local flash forensics.
- **PostgreSQL MVCC & WAL**: Deleted tuples remain in dead tuple space until `VACUUM` runs and in Write-Ahead Logs / automated database backups until backup retention expires. Because Relay stores **only AES-256-GCM ciphertexts**, residual Relay storage exposure reveals zero plaintext notification content.
