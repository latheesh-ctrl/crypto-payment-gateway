# Crypto Payment Gateway

A multi-merchant, custodial crypto payment gateway. Merchants accept deposits and send cashouts in crypto on
Tron, Ethereum, Bitcoin, BNB Smart Chain and Arbitrum, with a double-entry ledger as the source of truth.

- Architecture and roadmap: [docs/architecture.md](docs/architecture.md)
- Deposits and cashouts design: [docs/payment-flows.md](docs/payment-flows.md)

## Repository layout

| Path | What |
|---|---|
| `libs/common` | Shared value types: `Money` (exact minor units), prefixed ULID `Ids`, `ApiException` |
| `services/gateway-api` | Spring Boot 4 service: merchants, users, API keys, asset registry, ledger, balances, idempotency, audit log, webhooks |
| `deploy/local` | Docker Compose for local Postgres, Redis and NATS |
| `docs` | Architecture and design documents |

The service is organised by module (`merchant`, `user`, `apikey`, `asset`, `ledger`, `idempotency`, `audit`,
`outbox`, `webhook`, `security`) with no
cross-module table access, so modules can be split into separate services as load requires.

## Running locally

Requirements: JDK 21 and Docker.

```bash
docker compose -f deploy/local/docker-compose.yml up -d
ADMIN_TOKEN=dev-admin ./gradlew :services:gateway-api:bootRun
```

Create a merchant and an API key (operator routes, local only):

```bash
curl -s -X POST localhost:8080/internal/v1/merchants -H 'X-Admin-Token: dev-admin' \
  -H 'Content-Type: application/json' -d '{"name":"Acme","email":"ops@acme.test","country":"AE"}'
curl -s -X POST localhost:8080/internal/v1/merchants/<merchant id>/api-keys -H 'X-Admin-Token: dev-admin' \
  -H 'Content-Type: application/json' -d '{"name":"server"}'
curl -s localhost:8080/v1/assets -H 'Authorization: Bearer <secret>'
```

## Merchant API conventions

- Authenticate with `Authorization: Bearer sk_test_...` (sandbox) or `sk_live_...` (live).
- JSON fields are `snake_case`. Amounts are decimal strings in whole units of the asset.
- Every `POST` needs an `Idempotency-Key` header. Retrying with the same key and body returns the original
  response (with `Idempotent-Replayed: true`); the same key with a different body returns 422.
- Errors are `application/problem+json` with a stable `code`.

| Endpoint | Purpose |
|---|---|
| `GET /v1/assets` | Supported chains and tokens |
| `GET /v1/balances` | Merchant balances |
| `POST /v1/users`, `GET /v1/users`, `GET /v1/users/{id}` | Register and look up your end users (`external_ref` is your id for them) |
| `GET /v1/users/{id}/balances` | A user's available, pending and reserved balances |
| `POST /v1/webhook_endpoints`, `GET /v1/webhook_endpoints`, `POST /v1/webhook_endpoints/{id}/disable` | Webhook subscriptions |

### Verifying webhooks

Each delivery carries `CPG-Signature: t=<unix seconds>,v1=<hex>` where `v1` is
`HMAC-SHA256(endpoint secret, t + "." + raw body)`. Recompute it over the raw body, compare in constant time and
reject timestamps more than five minutes old. Deliveries are at-least-once, so de-duplicate on `CPG-Event-Id`.
Failed deliveries retry with exponential backoff for about three days.

## Tests

```bash
./gradlew build
```

Integration tests start PostgreSQL with Testcontainers, so Docker must be running.

## Ledger rules

- Amounts are integers in the asset's smallest unit (`NUMERIC(78,0)`), never floating point.
- Every posting balances per asset; the database re-checks this at commit.
- Ledger rows are append-only; corrections are reversing postings.
- Each posting has a unique `reference`, so retries and duplicate events cannot double-credit.
- Merchant balances can never go negative.

## Configuration

| Variable | Purpose |
|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL connection |
| `API_KEY_PEPPER` | Server secret mixed into API key hashes (required outside local dev) |
| `ADMIN_TOKEN` | Temporary operator credential for `/internal/**`; unset disables those routes |
| `GATEWAY_MODE` | `test` (sandbox, accepts `sk_test_` keys) or `live` |
| `DATA_ENCRYPTION_KEY` | Base64 AES-256 key encrypting webhook secrets at rest (required outside local dev) |
| `SERVER_FORWARD_HEADERS_STRATEGY` | Set to `native` behind a trusted load balancer so API key IP allowlists see the client address |
| `CHECKOUT_BASE_URL` | Origin of the hosted payment page (payment URLs and CORS), e.g. `https://pay.example.com` |
| `JOBS_ENABLED` | Run background loops (webhook delivery, chain watchers, deposit verification retries) in this instance (default `true`) |
| `WALLET_<CHAIN>_XPUB` | Account-level extended public key per chain for deposit addresses (TRON, ETHEREUM, BITCOIN, BSC, ARBITRUM). Never a private key. |
| `INDEXER_<CHAIN>_ENABLED` | Scan this chain for deposits (default `false`) |
| `INDEXER_<CHAIN>_RPC_URL` | Node or provider the watcher scans: EVM JSON-RPC, Tron full-node HTTP API, or Bitcoin Core RPC (credentials may go in the URL) |
| `INDEXER_<CHAIN>_VERIFY_RPC_URLS` | Comma-separated independent providers that must all confirm a deposit before it is credited. Without them deposits wait at finality and are never credited. Use full nodes (receipts and transaction lookup required). |
| `INDEXER_TRON_API_KEY` | TronGrid API key, if the Tron provider needs one |
