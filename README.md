# PhoneBridge — End-to-End Encrypted One-Way Notification Bridge

PhoneBridge delivers selected notifications and calendar reminders from a **Primary Android device (Tecno)** to a **Secondary Android device (Samsung)** across any internet connection without exposing plaintext content to the relay server.

## Key Capabilities
- **End-to-End Encryption**: RFC 9382 SPAKE2-P256 pairing + Android Keystore ECDSA identity keys + HKDF-SHA256 + AES-256-GCM AEAD with deterministic unique nonces (`key_epoch || sequence_number`).
- **Transactional Durability**: Room transactional outbox (Primary), PostgreSQL + Flyway relay storage, and Room transactional inbox (Secondary) with full deduplication.
- **Dual-Path Delivery**: Authenticated WSS (`wss://`) push with exponential backoff/jitter + paginated cursor-based HTTPS synchronization (`POST /v1/sync`), WorkManager recovery workers, ConnectivityManager callbacks, and `BOOT_COMPLETED` reboot recovery.
- **Apple-Minimalist Compose UI**: Clean grouped inset surfaces, dark mode support, adaptive `NavigationBar`/`NavigationRail`, and OEM-specific Tecno HiOS / Samsung One UI diagnostics.

## Repository Layout
- `/app`: Android Kotlin + Jetpack Compose application (`PhoneBridge`).
- `/relay-server`: Kotlin + Ktor + PostgreSQL + Flyway relay server, Dockerfile, Compose stack, and tests.
- `/docs`: Architecture, Protocol v1 Spec, API Reference, Security Threat Model, Deployment, and Troubleshooting guides.
