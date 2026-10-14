# Crypto Payment Gateway

A multi-merchant, custodial crypto payment gateway. Merchants accept deposits and send cashouts in crypto on
Tron, Ethereum, Bitcoin, BNB Smart Chain and Arbitrum, with a double-entry ledger as the source of truth.

- Architecture and roadmap: [docs/architecture.md](docs/architecture.md)
- Deposits and cashouts design: [docs/payment-flows.md](docs/payment-flows.md)

## Repository layout

| Path | What |
|---|---|
| `libs/common` | Shared value types: `Money` (exact minor units), prefixed ULID `Ids`, `ApiException` |
| `services/gateway-api` | Spring Boot 4 service: merchants, API keys, asset registry, ledger, balances |
| `deploy/local` | Docker Compose for local Postgres, Redis and NATS |
| `docs` | Architecture and design documents |

The service is organised by module (`merchant`, `apikey`, `asset`, `ledger`, `outbox`, `security`) with no
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
