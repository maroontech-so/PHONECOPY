# Deployment, Backup & Monitoring Guide

## 1. Relay Server Deployment (Docker Compose + Caddy TLS)
1. Copy `/relay-server/config/env.prod.example` to `.env` in `/relay-server/` (never commit `.env`).
2. Provide a strong `DATABASE_PASSWORD` and your public DNS domain in `RELAY_DOMAIN` (ports 80/443 open for Let's Encrypt ACME).
3. Launch:
   ```bash
   cd relay-server
   docker compose up -d --build
   ```
4. Flyway automatically applies `V1__initial_schema.sql` and `V2__rate_limit_and_audit.sql` on boot.

## 2. Monitoring & Health Checks
- Poll `GET /v1/health` (verifies PostgreSQL `SELECT 1` + active WSS count).
- Scrape `GET /v1/metrics` for `active_relationships`, `pending_encrypted_events`, `active_ws_connections`, and `rate_limit_rejections`.

## 3. Backup & Recovery
- **PostgreSQL**: Run encrypted `pg_dump --format=custom` backups on a cron schedule (`pg_restore` to recover). Note that `encrypted_events` only contains short-lived ciphertext blobs.
- **Android**: Devices hold unique `AndroidKeyStore` keys; if a phone is lost or wiped, revoke the relationship from the remaining device (`Pairing -> Revoke Pairing`) and re-pair with a fresh 6-digit SPAKE2 session.
