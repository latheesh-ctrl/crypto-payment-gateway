# API reference

Status as of 2026-10-14 (code on `main` through PR #6). Every endpoint in sections 2 to 5 is **live**. Section 7
lists endpoints that are **planned** and not built yet.

Contents:
1. [Conventions](#1-conventions)
2. [Merchant API `/v1`](#2-merchant-api-v1)
3. [Checkout API `/checkout/v1`](#3-checkout-api-checkoutv1)
4. [Operator API `/internal/v1`](#4-operator-api-internalv1)
5. [Health and metrics](#5-health-and-metrics)
6. [Webhook events](#6-webhook-events)
7. [Planned endpoints](#7-planned-endpoints)
8. [Status values](#8-status-values)
9. [Error codes](#9-error-codes)

## 1. Conventions

### Three API surfaces

| Surface | Who calls it | Authentication | Exposure |
|---|---|---|---|
| `/v1/**` | Merchant servers only (never a browser or game client) | API key + request signature | Public |
| `/checkout/v1/**` | The hosted payment page in the player's browser | Per-session checkout token | Public, CORS for the checkout origin only, `GET` only |
| `/internal/v1/**` | Platform operators | `X-Admin-Token` header (temporary until admin SSO) | Internal network only; disabled when `ADMIN_TOKEN` is unset |

### Merchant request headers

| Header | When | Notes |
|---|---|---|
| `Authorization: Bearer sk_test_…` / `sk_live_…` | Every request | `sk_test_` keys work only when the gateway runs in `test` mode, `sk_live_` only in `live` |
| `CPG-Timestamp`, `CPG-Nonce`, `CPG-Signature` | Every request on live keys; on test keys when `require_signed_requests` is on, or whenever sent | See [merchant-authentication.md](merchant-authentication.md#signing-requests) for the canonical string, test vector and sample code |
| `Idempotency-Key` | Every `POST` | 1 to 255 characters. Same key and same body replays the stored response with `Idempotent-Replayed: true`; same key, different body is `422 idempotency_key_reused`; a request still running is `409 idempotency_request_in_progress` |
| `Content-Type: application/json` | Requests with a body | |

### Formats

- JSON field names are `snake_case`.
- Amounts are **decimal strings** in whole units of the asset (`"25.000000"`), never numbers. They may have at most
  the asset's `decimals` places.
- Timestamps are ISO 8601 UTC strings.
- IDs are prefixed ULIDs: `mch_` merchant, `key_` API key, `usr_` user, `addr_` address, `dep_` deposit,
  `ps_` payment session, `we_` webhook endpoint, `evt_` event, `whd_` webhook delivery, `ltx_` ledger transaction.
- Assets are identified as `SYMBOL_CHAIN`, for example `USDT_TRON`, `USDC_ARBITRUM`, `BTC_BITCOIN`.
- Chains (networks): `TRON`, `ETHEREUM`, `BITCOIN`, `BSC`, `ARBITRUM`.

### Lists and pagination

List responses look like `{"data": [...], "has_more": false}`. Pass `limit` (1 to 100, default 20) and
`starting_after=<last id you received>` to page; results are newest first.

### Errors

Errors are `application/problem+json` with a stable `code`:

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400,
  "code": "validation_failed", "detail": "Request validation failed",
  "errors": ["amount: must be a positive decimal string"] }
```

Full list in [section 9](#9-error-codes).

### Scopes

Each route needs a scope on the API key. New keys get every scope except `payouts:write` unless you choose.

| Scope | Routes |
|---|---|
| `read` | Every `GET /v1/**` except the two below |
| `deposits:write` | Every other `POST`/`PATCH` under `/v1/**` (users, addresses, payment sessions, sandbox) |
| `webhooks:manage` | All of `/v1/webhook_endpoints/**` and `/v1/webhook_events/**` (including `GET`) |
| `keys:manage` | All of `/v1/api_keys/**` (including `GET`) |
| `payouts:write` | `POST /v1/payouts/**` (planned endpoints) |

### Rate limits

Per API key: the merchant's `rate_limit_per_second` (default 25) sustained, with bursts up to twice that. Payout
routes get a fifth. Over the limit returns `429 rate_limited` with `Retry-After`. The checkout API allows 5 requests
per second per IP address.

## 2. Merchant API `/v1`

Quick index:

| Method and path | Scope | Purpose |
|---|---|---|
| `GET /v1/assets` | read | Chains and tokens accepted |
| `GET /v1/balances` | read | Merchant ledger balances |
| `GET /v1/liquidity` | read | Merchant liquidity per asset |
| `POST /v1/users` | deposits:write | Register a player |
| `GET /v1/users` | read | List or find players |
| `GET /v1/users/{id}` | read | One player |
| `GET /v1/users/{id}/balances` | read | A player's balances |
| `POST /v1/users/{id}/deposit_addresses` | deposits:write | Get or create a player's permanent address on a chain |
| `GET /v1/users/{id}/deposit_addresses` | read | A player's addresses |
| `POST /v1/payin_addresses` | deposits:write | Get or create the merchant's liquidity pay-in address on a chain |
| `GET /v1/payin_addresses` | read | The merchant's pay-in addresses |
| `GET /v1/deposits` | read | List deposits |
| `GET /v1/deposits/{id}` | read | One deposit |
| `POST /v1/payment_sessions` | deposits:write | Create an order-bound deposit session |
| `GET /v1/payment_sessions` | read | List or find sessions |
| `GET /v1/payment_sessions/{id}` | read | One session |
| `POST /v1/payment_sessions/{id}/cancel` | deposits:write | Cancel an unpaid session |
| `POST /v1/sandbox/simulate_deposit` | deposits:write | Sandbox only: pay an address without a chain |
| `POST /v1/webhook_endpoints` | webhooks:manage | Register an endpoint |
| `GET /v1/webhook_endpoints` | webhooks:manage | List endpoints |
| `POST /v1/webhook_endpoints/{id}/disable` | webhooks:manage | Stop deliveries to an endpoint |
| `POST /v1/webhook_endpoints/{id}/rotate_secret` | webhooks:manage | New signing secret with overlap |
| `GET /v1/webhook_events` | webhooks:manage | Event log |
| `GET /v1/webhook_events/{id}` | webhooks:manage | One event with every delivery attempt |
| `POST /v1/webhook_events/{id}/redeliver` | webhooks:manage | Send an event again |
| `GET /v1/api_keys` | keys:manage | List keys (never secrets) |
| `POST /v1/api_keys` | keys:manage | Create a key |
| `POST /v1/api_keys/{id}/roll` | keys:manage | Replace a key with overlap |
| `POST /v1/api_keys/{id}/revoke` | keys:manage | Revoke a key now |

### 2.1 Assets

#### `GET /v1/assets`

Enabled assets on enabled chains.

```json
{ "data": [
  { "id": "USDT_TRON", "chain": "TRON", "chain_name": "Tron", "symbol": "USDT", "name": "Tether USD",
    "contract_address": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "decimals": 6, "confirmations": 19,
    "accept_payments": true }
] }
```

v1 assets: `TRX_TRON`, `USDT_TRON`, `ETH_ETHEREUM`, `USDT_ETHEREUM`, `USDC_ETHEREUM`, `BTC_BITCOIN`,
`USDT_BSC`, `ETH_ARBITRUM`, `USDC_ARBITRUM`, `USDT_ARBITRUM`. `BNB_BSC` exists for gas only
(`accept_payments: false`).

### 2.2 Balances and liquidity

#### `GET /v1/balances`

The merchant's own ledger balances per asset.

```json
{ "data": [ { "asset": "USDT_TRON", "available": "0.000000", "pending": "0.000000", "reserved": "0.000000" } ] }
```

#### `GET /v1/liquidity`

What funds the merchant's players' cashouts.

```json
{ "data": [ { "asset": "USDT_ARBITRUM", "available": "1250.000000", "allocated": "0.000000",
              "owed_to_users": "300.000000" } ] }
```

| Field | Meaning |
|---|---|
| `available` | Liquidity free to fund cashouts (pay-ins plus confirmed player deposits, minus deposit fees) |
| `allocated` | Earmarked for cashouts in flight (used once cashouts ship) |
| `owed_to_users` | Sum of the merchant's players' balances (`platform_managed` merchants only; 0 for `merchant_managed`) |

### 2.3 Users (players)

#### `POST /v1/users`

```json
{ "external_ref": "player-42", "metadata": { "vip": true } }
```

- `external_ref`: required, your ID for the player, 1 to 128 printable ASCII characters without spaces, unique per
  merchant.
- `metadata`: optional, up to 50 keys.

`201`:

```json
{ "id": "usr_01JA…", "merchant_id": "mch_01JA…", "external_ref": "player-42", "status": "active",
  "kyc_status": "not_required", "metadata": { "vip": true }, "created_at": "2026-10-14T17:00:00Z" }
```

Errors: `409 user_exists`. Emits `user.created`.

#### `GET /v1/users`

Query: `external_ref` (exact lookup, returns 0 or 1 result), or `limit` and `starting_after` to page.

#### `GET /v1/users/{id}`

`404 resource_missing` if the user is not yours.

#### `GET /v1/users/{id}/balances`

```json
{ "data": [ { "asset": "USDT_TRON", "available": "40.000000", "pending": "0.000000", "reserved": "0.000000" } ] }
```

Only meaningful for `platform_managed` merchants; `merchant_managed` players have no balance here.

### 2.4 Deposit addresses

#### `POST /v1/users/{id}/deposit_addresses`

```json
{ "chain": "TRON" }
```

Returns the player's permanent address on that chain, creating it on the first call (later calls return the same
address). Any asset accepted on the chain can be sent to it.

```json
{ "id": "addr_01JA…", "chain": "TRON", "address": "TQ…", "owner_type": "user", "owner_id": "usr_01JA…",
  "created_at": "…" }
```

Errors: `400 unsupported_chain`, `503 chain_unavailable` (no wallet key configured for that chain yet).

#### `GET /v1/users/{id}/deposit_addresses`

`{"data": [ …addresses… ]}`.

#### `POST /v1/payin_addresses`, `GET /v1/payin_addresses`

Same as above for the merchant's own liquidity pay-in address (`owner_type: "merchant"`).

### 2.5 Deposits

A deposit is one on-chain transfer to one of your addresses (player address, pay-in address or session address).

#### `GET /v1/deposits`

Query: `user_id`, `type=merchant_payin`, `status`, `limit`, `starting_after`.

#### `GET /v1/deposits/{id}`

```json
{ "id": "dep_01JA…", "type": "user_deposit", "user_id": "usr_01JA…", "payment_session_id": null,
  "chain": "TRON", "asset": "USDT_TRON", "address": "TQ…", "amount": "40.000000", "fee": "0.000000",
  "tx_hash": "a1b2…", "output_index": 0, "confirmations": 19, "required_confirmations": 19,
  "status": "completed", "status_reason": null, "simulated": false,
  "detected_at": "…", "completed_at": "…" }
```

`type` is `user_deposit`, `session_payment` or `merchant_payin`. `fee` is the merchant's deposit fee, charged on
player money only. Statuses in [section 8](#8-status-values).

### 2.6 Payment sessions

Full guide: [payment-sessions.md](payment-sessions.md).

#### `POST /v1/payment_sessions`

```json
{ "player_ref": "gk_player_8812", "merchant_order_id": "GK-DEP-000123", "network": "ARBITRUM",
  "asset": "USDT", "amount": "25.00", "expires_in_seconds": 1800,
  "return_url": "https://game.example/wallet", "metadata": { "table": "vip-3" } }
```

| Field | Rules |
|---|---|
| `player_ref` | Required, up to 128 characters |
| `merchant_order_id` | Required, unique per merchant, up to 128 of `A-Z a-z 0-9 . _ : -` |
| `network` | Required chain code |
| `asset` | Required symbol accepted on that network (`USDT`, not `USDT_ARBITRUM`) |
| `amount` | Required positive decimal string |
| `expires_in_seconds` | 300 to 86400, default 1800 |
| `return_url` | Optional http(s) URL, shown as a back link on the hosted page |
| `metadata` | Optional object, returned as is |

`201` for a new session, `200` with the existing session when the same order is sent again with the same details.

```json
{ "id": "ps_01JA…", "object": "payment_session", "status": "open",
  "player_ref": "gk_player_8812", "merchant_order_id": "GK-DEP-000123",
  "network": "ARBITRUM", "asset": "USDT", "asset_id": "USDT_ARBITRUM",
  "amount": "25.000000", "amount_received": "0.000000", "address": "0x…",
  "required_confirmations": 20,
  "payment_url": "https://pay.example.com/s/ps_01JA…#t=…",
  "return_url": "https://game.example/wallet", "metadata": { "table": "vip-3" },
  "payments": [], "expires_at": "…", "created_at": "…", "updated_at": "…", "completed_at": null }
```

`payments` lists each deposit to the session (`deposit_id`, `tx_hash`, `asset_id`, amount, status…).

Errors: `400 unsupported_asset`, `400 invalid_expiry`, `400 invalid_amount`, `409 order_conflict`.

#### `GET /v1/payment_sessions/{id}`

#### `GET /v1/payment_sessions`

Query: `merchant_order_id` (exact lookup), or `player_ref`, `status`, `limit`, `starting_after`.

#### `POST /v1/payment_sessions/{id}/cancel`

Only while `open` and nothing has arrived; otherwise `409 session_not_cancellable`. Emits
`payment_session.cancelled`.

### 2.7 Sandbox

#### `POST /v1/sandbox/simulate_deposit`

Test mode only (`404` in live mode). Simulates a confirmed transfer to one of your addresses, skipping the chain and
independent verification. Everything downstream (ledger, session, webhooks) runs as in production.

```json
{ "address": "0x…", "asset": "USDT_ARBITRUM", "amount": "25" }
```

Returns the deposit (`simulated: true`). Errors: `404` address not yours, `400 asset_chain_mismatch`,
`400 invalid_amount`.

### 2.8 Webhook endpoints

#### `POST /v1/webhook_endpoints`

```json
{ "url": "https://game.example/hooks/cpg", "event_types": ["*"] }
```

`event_types` is `["*"]` or a list of exact types such as `payment_session.completed`. `201`:

```json
{ "id": "we_01JA…", "url": "https://game.example/hooks/cpg", "event_types": ["*"], "status": "enabled",
  "created_at": "…", "secret": "whsec_…" }
```

The secret is shown only here. URL rules: absolute HTTPS without credentials (HTTP allowed in sandbox); in live mode
private and loopback addresses are refused at send time. At most 10 enabled endpoints. Errors: `400 invalid_url`,
`400 too_many_webhook_endpoints`.

#### `GET /v1/webhook_endpoints`

#### `POST /v1/webhook_endpoints/{id}/disable`

#### `POST /v1/webhook_endpoints/{id}/rotate_secret`

Body optional: `{"overlap_hours": 24}` (0 to 168, default 24). Returns the endpoint with the new `secret`. The old
secret keeps signing alongside the new one until the overlap ends, so the signature header carries two `v1` values.

### 2.9 Webhook events and deliveries

#### `GET /v1/webhook_events`

Query: `type`, `limit`, `starting_after`. Each item: `id`, `type`, `created_at`, `data`.

#### `GET /v1/webhook_events/{id}`

The event plus `deliveries`: per endpoint `id`, `endpoint_id`, `url`, `status` (`pending`, `succeeded`, `failed`),
`attempts`, `next_attempt_at`, `last_status_code`, `last_error`, `completed_at`, and `attempt_log` with each
attempt's `status_code`, `error`, `duration_ms`, `attempted_at`.

#### `POST /v1/webhook_events/{id}/redeliver`

Queues the event again now for every enabled endpoint that had a delivery for it. `409 nothing_to_redeliver` if
none. Audited.

### 2.10 API keys

Detail: [merchant-authentication.md](merchant-authentication.md).

#### `GET /v1/api_keys`

```json
{ "data": [ { "id": "key_01JA…", "mode": "test", "name": "server", "last4": "9f2c",
  "scopes": ["read", "deposits:write", "webhooks:manage", "keys:manage"], "ip_allowlist": [],
  "created_at": "…", "last_used_at": "…", "expires_at": null, "revoked_at": null } ] }
```

#### `POST /v1/api_keys`

```json
{ "name": "payout-worker", "scopes": ["read", "payouts:write"], "ip_allowlist": ["203.0.113.10"],
  "expires_in_days": 365 }
```

`201` with `secret` (`sk_…`) and `signing_secret` (`ss_…`), shown once. A key can only grant scopes it has
(`403 scope_not_grantable`). Live keys with `payouts:write` need an IP allowlist (`400 ip_allowlist_required`).

#### `POST /v1/api_keys/{id}/roll`

Body optional: `{"overlap_hours": 24}` (0 to 168). `201` with a new key; the old one stops when the overlap ends.

#### `POST /v1/api_keys/{id}/revoke`

Immediate. `409 key_inactive` if already revoked or expired.

## 3. Checkout API `/checkout/v1`

Used by the hosted payment page. The page reads the token from the `payment_url` fragment and sends it as a header.

#### `GET /checkout/v1/sessions/{id}`

Header: `X-Checkout-Token: <token>`. A wrong token and an unknown session both return `404`.

```json
{ "id": "ps_01JA…", "status": "confirming", "network": "ARBITRUM", "network_name": "Arbitrum One",
  "asset": "USDT", "token_contract": "0xFd08…", "amount": "25.000000", "amount_received": "0.000000",
  "address": "0x…", "confirmations": 7, "required_confirmations": 20, "expires_at": "…",
  "return_url": "https://game.example/wallet" }
```

No player, order or merchant data. Read only. 5 requests per second per IP.

## 4. Operator API `/internal/v1`

Header `X-Admin-Token: <ADMIN_TOKEN>`. Never exposed on the public ingress. All actions are audited as
`bootstrap-admin` until per-operator SSO exists.

#### `POST /internal/v1/merchants`

```json
{ "name": "GameKing", "email": "ops@gameking.example", "country": "AE" }
```

`country` is an ISO 3166-1 alpha-2 code. `201` with the merchant. `409 email_taken`.

#### `GET /internal/v1/merchants/{id}`

```json
{ "id": "mch_01JA…", "name": "GameKing", "email": "ops@gameking.example", "status": "active",
  "kyc_status": "not_required", "country": "AE", "player_balances": "platform_managed",
  "require_signed_requests": false, "rate_limit_per_second": 25, "created_at": "…" }
```

#### `PATCH /internal/v1/merchants/{id}/settings`

Any subset of:

```json
{ "player_balances": "merchant_managed", "require_signed_requests": true, "rate_limit_per_second": 50 }
```

`rate_limit_per_second` is 1 to 10000. Audited as `merchant.settings_updated`.

#### `POST /internal/v1/merchants/{id}/api-keys`

```json
{ "name": "server", "scopes": ["read", "deposits:write", "webhooks:manage", "keys:manage"],
  "ip_allowlist": ["203.0.113.0/24"] }
```

Operator-issued keys may carry any scope, including `payouts:write`. `201` with both secrets, shown once.

## 5. Health and metrics

| Path | Auth | Purpose |
|---|---|---|
| `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | None | Probes for the load balancer and Kubernetes |
| `GET /actuator/info` | None | Build info |
| `GET /actuator/prometheus` | Not public; scrape from inside the cluster | Metrics, including chain head and scan lag per chain |

## 6. Webhook events

Envelope:

```
POST <your url>
Content-Type: application/json
User-Agent: CPG-Webhooks/1.0
CPG-Event-Id: evt_01JA…
CPG-Signature: t=1760464800,v1=<hex>[,v1=<hex during rotation>]

{ "id": "evt_01JA…", "type": "payment_session.completed", "created_at": "…", "livemode": false,
  "data": { …the object, same shape as the API… } }
```

Verify `HMAC-SHA256(secret, t + "." + raw_body)`, reject `t` older than 5 minutes, dedupe on `id`, answer 2xx within
10 seconds. Retries: 10 s doubling, capped at 6 h, 16 attempts (about 3 days).

| Event | `data` | When |
|---|---|---|
| `user.created` | user | A player was registered |
| `deposit.detected` | deposit | Transfer seen to a player or pay-in address (also on revival after a reorg) |
| `deposit.completed` | deposit | Final, verified and credited |
| `deposit.reversed` | deposit | Removed by a reorg before finality |
| `deposit.unsupported` | deposit | Token not accepted on that chain, or zero amount; never credited |
| `deposit.needs_review` | deposit | Verification mismatch, reorg after completion, or a re-mined transaction already credited |
| `payment_session.pending` | session | Transfer seen |
| `payment_session.confirming` | session | Confirmations accumulating |
| `payment_session.completed` | session | Exactly `amount` arrived and was verified |
| `payment_session.underpaid` | session | Less than `amount` arrived |
| `payment_session.overpaid` | session | More than `amount` arrived |
| `payment_session.late_payment` | session | Paid after `expires_at` |
| `payment_session.expired` | session | Nothing arrived in time |
| `payment_session.cancelled` | session | Cancelled by the merchant |
| `payment_session.needs_review` | session | Wrong asset sent, or a payment needs review |
| `payment_session.reversed` | session | A reorg removed an unconfirmed payment; back to `open` or `expired` |

Planned: `payout.processing`, `payout.broadcast`, `payout.completed`, `payout.failed`, `payout.cancelled`,
`payout.needs_review`, `liquidity.low`.

## 7. Planned endpoints

Not built. Shapes come from the agreed designs and may change.

| Endpoint | Purpose | Phase |
|---|---|---|
| `POST /v1/payouts`, `GET /v1/payouts/{id}`, `GET /v1/payouts?merchant_payout_id=`, `POST /v1/payouts/{id}/cancel` | Player cashouts (scope `payouts:write`, signed, IP allowlisted) | 2c, in progress |
| Merchant payout endpoints (own withdrawals to whitelisted addresses) | Merchant payouts | After 2c |
| `GET /v1/reports/transactions?from=&to=&format=csv`, `GET /v1/reconciliation/daily?date=` | Reports and reconciliation | 2e |
| `POST /v1/exchange/quotes`, `POST /v1/exchanges` | Cross-chain exchange | Later |
| `POST /hooks/{provider}` | Signed callbacks from custody, AML or KYC providers | With those integrations |
| `/internal/v1/...` approvals, limits, deposit review and recovery, reconciliation, wallets | Admin console back end | With the admin console |
| Dashboard session auth (login, 2FA, team members) | Merchant dashboard and admin console | With the dashboards |

## 8. Status values

| Object | Statuses |
|---|---|
| Deposit | `detected`, `confirming`, `completed`, `unsupported`, `reversed`, `needs_review` |
| Payment session | `open`, `pending`, `confirming`, `completed`, `underpaid`, `overpaid`, `late_payment`, `expired`, `cancelled`, `needs_review` |
| Webhook endpoint | `enabled`, `disabled` |
| Webhook delivery | `pending`, `succeeded`, `failed` |
| Merchant | `active`, `suspended`, `closed` |
| User | `active`, `blocked`, `closed` |
| KYC (merchant and user) | `not_required`, `required`, `pending`, `verified`, `rejected` (module planned; stays `not_required` today) |
| Payout (planned) | `pending`, `awaiting_liquidity`, `processing`, `signing`, `broadcast`, `confirming`, `completed`, `failed`, `cancelled`, `needs_review` |

## 9. Error codes

| Status | `code` | Meaning |
|---|---|---|
| 400 | `validation_failed` | Body or query failed validation; see `errors` |
| 400 | `idempotency_key_required` | `POST` without a valid `Idempotency-Key` |
| 400 | `invalid_amount` | Not a positive decimal, or too many decimal places for the asset |
| 400 | `unsupported_chain`, `unsupported_asset`, `asset_chain_mismatch` | Chain or asset not accepted |
| 400 | `invalid_expiry` | `expires_in_seconds` outside 300 to 86400 |
| 400 | `invalid_url`, `too_many_webhook_endpoints` | Webhook endpoint rules |
| 400 | `invalid_overlap` | Overlap outside 0 to 168 hours |
| 400 | `invalid_scopes`, `invalid_ip_allowlist`, `ip_allowlist_required` | API key rules |
| 401 | `unauthenticated` | Missing, unknown, expired or revoked key |
| 401 | `signature_required`, `signature_unavailable`, `invalid_signature` | Request signing |
| 401 | `invalid_timestamp`, `timestamp_out_of_range`, `invalid_nonce`, `nonce_reused` | Replay protection |
| 403 | `forbidden` | Key lacks the scope |
| 403 | `ip_not_allowed` | Source IP not on the key's allowlist |
| 403 | `scope_not_grantable` | Creating a key with a scope you don't have |
| 404 | `resource_missing` | Not found, or not yours |
| 409 | `user_exists`, `email_taken` | Duplicate |
| 409 | `order_conflict` | Same `merchant_order_id`, different details |
| 409 | `session_not_cancellable` | Session already paid or closed |
| 409 | `key_inactive` | Key already revoked or expired |
| 409 | `nothing_to_redeliver` | No enabled endpoint had this event |
| 409 | `idempotency_request_in_progress` | Same key still being processed; retry shortly |
| 413 | `request_too_large` | Body too large |
| 422 | `idempotency_key_reused` | Same key, different request |
| 429 | `rate_limited` | Wait `Retry-After` seconds |
| 500 | `internal_error` | Unexpected; safe to retry with the same `Idempotency-Key` |
| 503 | `chain_unavailable` | Deposits on that chain are not configured yet |
