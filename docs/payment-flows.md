# Deposits and Cashouts: Gap Analysis and Updated Plan

Status: proposal for review (2026-10-14). No existing code has been changed for this; it builds on the Phase 0 scaffold and on [gateway-architecture.md](gateway-architecture.md).

## 1. Who "the user" is

The gateway's customers are **merchants**. A merchant's own customers (players, shoppers) are **end users**. This plan assumes:

- End users deposit and cash out **through a merchant's integration**. The merchant calls our API (or sends the end user to our hosted page), and every deposit and cashout carries the merchant's `customer_reference` for that end user.
- Money is held in the **merchant's balance** in our ledger, per asset. The merchant keeps each end user's balance in its own system, which is how most gaming and exchange integrations work.
- Optional later: per-end-user sub-balances inside our ledger (wallet-as-a-service). The schema below leaves room for it without rework. **[DECISION]**

"Payment gateway / funding method" here means on-chain crypto on the v1 chains, with no banking or fiat (decided earlier). The external provider is our custody provider (MPC) for sending, plus the chain indexers and RPC providers for receiving.

## 2. What exists today (Phase 0)

| Area | Built | Notes |
|---|---|---|
| Merchants | `merchants` table, operator create/get API | KYC optional (`kyc_status`, default `not_required`) |
| API keys | `api_keys`, HMAC-peppered hashes, `sk_test_` / `sk_live_`, bearer auth filter | Sandbox and live are separate deployments |
| Asset registry | `chains`, `assets`, seeded with all v1 chains/tokens, `GET /v1/assets` | New tokens are rows |
| Ledger | Double-entry, append-only (DB triggers), per-asset balance checked at commit, idempotent by `reference`, row-locked balances, merchant balances can't go negative | Account kinds: pending, available, deposit_wallets, hot_wallet, cold_wallet, gas, fees, network_fee_expense |
| Balances API | `GET /v1/balances` (available, pending as decimal strings) | |
| Outbox | `outbox_events` written in the same transaction as ledger postings | Relay to NATS not built yet |
| Errors | RFC 9457 problem+json with stable `code` | |
| Tests | 15 tests incl. concurrency, idempotency, append-only, DB-level balance check (Testcontainers Postgres) | All green |

## 3. Gaps against the requirements

| Requirement | Status | What's missing |
|---|---|---|
| **A. Deposits** | | |
| Create and track deposit requests | Missing | `deposits` table, API, state machine |
| Handle gateway responses, webhooks, async callbacks | Missing | Indexer events consumer, inbound provider webhook endpoint, callback log |
| Verify callback authenticity, block forgeries | Missing | Signature verification, replay protection, re-verification against the chain |
| Correct status updates | Missing | Guarded state transitions with history |
| Credit only after verified confirmation | Partly | Ledger supports pending vs available; no confirmation logic yet |
| No duplicate credits | Partly | Ledger `reference` is unique; needs deposit-level unique keys (chain + tx hash + output index) |
| Full history and audit trail | Partly | Ledger is append-only; needs status history and an audit log |
| **B. Cashouts** | | |
| Eligible users request cashouts | Missing | API, eligibility rules |
| Validate balance and limits | Partly | Ledger blocks negative balances; no limits engine |
| Reserve funds atomically against concurrent requests | Partly | Row-locked postings exist; needs a `reserved` account kind and reserve/release postings |
| Statuses pending, processing, completed, failed, cancelled | Missing | `cashouts` table and state machine |
| Payout provider integration | Missing | `PayoutProvider` adapter for the custody provider |
| Callbacks, timeouts, retries, delayed confirmations | Missing | Callback handler, status poller, safe-retry rules, chain confirmation tracking |
| Merchant notifications | Missing | Webhook delivery service (designed in the main plan, not built) |

Nothing built so far has to be rewritten. All changes are additive: new tables in a new migration, new account kinds, new modules.

## 4. Deposit design

### 4.1 Flow
1. Merchant calls `POST /v1/deposits` with asset, optional expected amount, `customer_reference`, `merchant_reference` and an `Idempotency-Key`. Or the end user uses a permanent per-customer deposit address (`POST /v1/customers/{ref}/deposit-addresses`), which is common for gaming.
2. Wallet service assigns a deposit address the platform controls (derived from an xpub on Bitcoin, from the MPC vault on EVM and Tron).
3. The chain indexer sees a transfer to a watched address and emits `chain.transfer.detected`. The deposit moves to `detected` and the ledger posts **debit deposit_wallets / credit merchant pending**.
4. The indexer tracks confirmations. When the chain's threshold is reached (and the block is still canonical), the deposit moves to `confirmed`. The ledger posts **debit merchant pending / credit merchant available (net of fee) + credit platform fees**.
5. Each status change writes an outbox event, which becomes a signed merchant webhook (`deposit.detected`, `deposit.confirmed`, ...).
6. A sweeper later moves funds from deposit addresses to the hot wallet (**debit hot_wallet / credit deposit_wallets**, gas to network_fee_expense).

### 4.2 States
`awaiting_payment → detected → confirming → completed`, plus `expired` (no payment in time), `underpaid` / `overpaid` (outside tolerance, still credited for what arrived, flagged for the merchant), `reversed` (chain reorg removed the transaction before final), `rejected` (AML hit, funds frozen pending review). Transitions go through one guarded method (`UPDATE ... WHERE status = :expected`) so two workers cannot apply conflicting changes.

### 4.3 Callback authenticity and forged requests
- **Chain events are internal**: indexers publish to NATS on a private network with per-service credentials. The public internet cannot send them.
- **External provider webhooks** (custody provider, any future third-party funding method) arrive at `/hooks/{provider}`. Each adapter verifies the provider's signature (HMAC or RSA, per provider), rejects timestamps older than 5 minutes, and records the provider's event id in `provider_callbacks` (unique on provider + event id) so replays are dropped.
- **Never trust the payload alone**: before crediting, the deposit service re-reads the transaction from at least one independent RPC provider and checks recipient, token contract, amount and confirmations. A forged or spoofed callback can at most trigger a check that fails.
- Fake-token protection: only transfers from contracts in the asset registry count. Lookalike tokens are ignored.

### 4.4 Duplicate-credit protection
- `deposits` unique on `(chain_code, tx_hash, output_index)`, where output index is the log index on EVM/Tron and the vout on Bitcoin.
- Ledger references derived from that key (`deposit:{chain}:{tx}:{idx}:detected`, `...:confirmed`), so even a reprocessed event cannot post twice.
- Merchant API idempotency via `Idempotency-Key` (stored with the response for 24h) and unique `merchant_reference` per merchant.

## 5. Cashout design

### 5.1 Flow
1. Merchant calls `POST /v1/cashouts` with asset, amount, destination address, `customer_reference`, `merchant_reference` and an `Idempotency-Key`.
2. **Validation**: merchant active, asset enabled, address valid for the chain (checksum, network), amount within min/max, velocity limits (per merchant and per `customer_reference`, daily and monthly), optional address whitelist, AML screening of the destination.
3. **Atomic reservation**: in one database transaction, insert the cashout as `pending` and post **debit merchant available / credit merchant reserved** for amount + fee. The ledger locks the balance rows and refuses to go negative, so two concurrent cashouts cannot spend the same funds (already proven by the concurrency test pattern).
4. **Approval**: auto-approved below the merchant's threshold, otherwise queued for an operator (two-person approval above a higher threshold).
5. **Processing**: the payout worker submits to the custody provider using our cashout id as the provider's idempotency key, and the status moves to `processing`.
6. **Broadcast and confirmation**: the provider callback or status poll gives the tx hash, and the indexer tracks confirmations. On final confirmation the status becomes `completed` and the ledger posts **debit merchant reserved / credit hot_wallet** plus fee to platform fees and actual network fee to network_fee_expense.
7. **Failure or cancel**: release with **debit merchant reserved / credit merchant available**. Cancel is allowed only before submission to the provider.

### 5.2 States
`pending → approved → processing → broadcast → completed`, plus `failed`, `cancelled`, `rejected` (limits, AML or operator), and `needs_review` (outcome unknown, see below). Every transition is recorded in `cashout_status_history` with actor and reason.

### 5.3 Timeouts, retries and delayed confirmations
- **Safe retries only**: a submission is retried only when the provider confirms it never received it, or when the provider supports idempotency keys (our cashout id), so a retry can never send twice.
- **Unknown outcome**: if a submission times out, the cashout is not failed or resubmitted. A poller queries the provider by our id with backoff. If it is still unknown after a limit, it moves to `needs_review` and alerts operations. Funds stay reserved throughout.
- **Delayed confirmations**: a broadcast transaction may take minutes or hours. It stays `broadcast` until the indexer confirms it. If a transaction is dropped or replaced (EVM nonce, Bitcoin RBF), the payout worker rebuilds with the same cashout id after checking the original can no longer confirm.
- **Hot wallet low**: cashouts queue in `approved` and operations is alerted to top up from warm or cold storage. Nothing fails because of liquidity.

## 6. Cross-cutting additions
- `audit_log`: append-only record of every operator and merchant action (who, what, before/after, IP).
- `idempotency_keys`: merchant API idempotency, scoped per merchant, stores the request hash and response.
- `provider_callbacks`: raw inbound webhooks with verification result, for forensics and replay.
- Outbox relay to NATS and the merchant webhook service (signed `deposit.*` and `cashout.*` events with retries).
- Daily reconciliation: on-chain balances against ledger per asset, and provider-side payout records against cashouts.
- Metrics and alerts: deposits stuck in confirming, cashouts in processing or needs_review beyond SLA, hot wallet below threshold.

## 7. Schema changes (new migration V3, additive)

```
deposit_addresses (id, merchant_id, customer_reference NULL, chain_code, address, derivation_ref, status, created_at)
  UNIQUE (chain_code, address)

deposits (id, merchant_id, customer_reference, merchant_reference, asset_id, address_id,
          expected_amount NULL, received_amount, status, chain_code, tx_hash, output_index,
          confirmations, block_number, detected_at, completed_at, expires_at, created_at, updated_at)
  UNIQUE (chain_code, tx_hash, output_index)
  UNIQUE (merchant_id, merchant_reference)

cashouts (id, merchant_id, customer_reference, merchant_reference, asset_id, amount, fee,
          destination_address, status, provider, provider_ref, tx_hash, network_fee,
          failure_code, failure_reason, approved_by, requested_at, completed_at, updated_at)
  UNIQUE (merchant_id, merchant_reference)

deposit_status_history / cashout_status_history (id, parent_id, from_status, to_status, actor, reason, at)

cashout_limits (merchant_id, asset_id, min_amount, max_amount, daily_limit, monthly_limit,
                per_customer_daily_limit, manual_approval_above)

idempotency_keys (merchant_id, key, request_hash, response_status, response_body, created_at)
  PRIMARY KEY (merchant_id, key)

provider_callbacks (id, provider, event_id, signature_valid, payload, received_at, processed_at)
  UNIQUE (provider, event_id)

audit_log (id, actor_type, actor_id, action, target_type, target_id, details jsonb, ip, at)  -- append-only
```

New ledger account kind: `MERCHANT_RESERVED` (credit-normal, cannot go negative). Existing tables are unchanged.

## 8. API additions

| Endpoint | Purpose |
|---|---|
| `POST /v1/deposits`, `GET /v1/deposits/{id}`, `GET /v1/deposits?customer_reference=` | Create and track deposits |
| `POST /v1/customers/{ref}/deposit-addresses` | Permanent deposit address per end user and chain |
| `POST /v1/cashouts`, `GET /v1/cashouts/{id}`, `GET /v1/cashouts` | Request and track cashouts |
| `POST /v1/cashouts/{id}/cancel` | Cancel before submission |
| `POST /hooks/{provider}` | Signed provider callbacks (not under `/v1`, no API key, signature required) |
| `/internal/v1/cashouts/{id}/approve`, `/reject` | Operator review |
| `/internal/v1/merchants/{id}/limits` | Configure cashout limits |

All `POST` endpoints require `Idempotency-Key`.

## 9. Updated delivery plan

| Phase | Scope |
|---|---|
| 0. Foundations (done, in review) | Repo, CI, merchants, API keys, asset registry, ledger core, balances |
| 1a. Platform plumbing | Idempotency keys, audit log, status-history pattern, outbox relay to NATS, merchant webhooks |
| 1b. Deposits (testnets) | Deposit addresses, deposits API and state machine, Tron + EVM indexers, then Bitcoin, confirmation and reorg handling, re-verification, sweeps |
| 2. Cashouts (testnets) | Reserved balances, limits engine, cashouts API and state machine, custody provider adapter, callbacks, poller, safe retries, operator approval |
| 3. Live beta | Mainnet, AML screening, reconciliation jobs, alerting, dashboards for deposits and cashouts, pilot merchants |
| 4. Scale | Per-end-user sub-balances (if chosen), more chains, multi-region |

## 10. Open questions
1. End-user balances: merchant-level balance with `customer_reference` tracking (proposed), or per-end-user balances held in our ledger?
2. Custody provider for sending cashouts: Fireblocks, Turnkey, Cobo, or self-hosted signing.
