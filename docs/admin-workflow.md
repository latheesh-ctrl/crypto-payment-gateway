# Admin workflow

Status as of 2026-10-14. Operators work today through the internal API (`/internal/v1`, four endpoints), service
configuration and the database. The **admin console** (Next.js, per-operator SSO, roles, approvals) is planned;
section 3 describes how it is meant to work.

## 1. What operators can do today (live)

### 1.1 Access

- Internal API: header `X-Admin-Token: <ADMIN_TOKEN>`. It is a single shared credential, compared in constant time,
  and the routes are disabled when `ADMIN_TOKEN` is unset. Expose `/internal/**` only on the internal network.
- Every operator action is written to the append-only `audit_log` as actor `bootstrap-admin`. Per-operator identity
  arrives with SSO.

### 1.2 Onboard a merchant

```bash
# 1. Create
curl -X POST $GW/internal/v1/merchants -H "X-Admin-Token: $T" -H 'Content-Type: application/json' \
  -d '{"name":"GameKing","email":"ops@gameking.example","country":"AE"}'

# 2. Settings (games keep their own player balances)
curl -X PATCH $GW/internal/v1/merchants/mch_…/settings -H "X-Admin-Token: $T" -H 'Content-Type: application/json' \
  -d '{"player_balances":"merchant_managed","require_signed_requests":true,"rate_limit_per_second":50}'

# 3. First API key (sandbox). Hand both secrets over through a secure channel; they are shown once.
curl -X POST $GW/internal/v1/merchants/mch_…/api-keys -H "X-Admin-Token: $T" -H 'Content-Type: application/json' \
  -d '{"name":"server"}'

# 4. Look it up any time
curl $GW/internal/v1/merchants/mch_… -H "X-Admin-Token: $T"
```

Go-live: issue an `sk_live_` key from the live deployment. A key that can create payouts must have an
`ip_allowlist`. Only operator-issued keys can carry `payouts:write` unless the merchant already has a key with it.

### 1.3 Configure chains and wallets

Done through environment variables per deployment (see the [README](../README.md#configuration)):

| Task | Setting |
|---|---|
| Enable deposit addresses on a chain | `WALLET_<CHAIN>_XPUB` (account-level extended **public** key from the custody provider; never a private key) |
| Turn on a chain watcher | `INDEXER_<CHAIN>_ENABLED=true`, `INDEXER_<CHAIN>_RPC_URL` |
| Independent verification (required to credit) | `INDEXER_<CHAIN>_VERIFY_RPC_URLS`, one or more providers different from the scan provider |
| Run background jobs only on some instances | `JOBS_ENABLED` |
| Hosted page origin | `CHECKOUT_BASE_URL` |

Enabling a new token on an existing chain is a row in the `assets` table plus the `enabled` and `accept_payments`
flags; no code change.

### 1.4 Monitor

- `GET /actuator/health/readiness` and `/liveness` for probes.
- `GET /actuator/prometheus` for metrics, including chain head and scan lag per chain.
- Deposits stuck at finality mean a verifier is unavailable; they retry every 30 seconds and are never credited until
  verification succeeds.

### 1.5 Handle exceptions (database only today)

There is no operator API for these yet; operators query the database directly, read-only:

| Situation | Where to look | What to do |
|---|---|---|
| Deposit or session in `needs_review` | `deposits`, `deposit_status_history`, `payment_sessions`, `payment_session_status_history` | Investigate the tx on explorers; contact the merchant. Any correction is a reversing ledger posting, never an edit |
| `unsupported` deposit (wrong token) | `deposits` with `status_reason` | Manual recovery once custody is integrated |
| Merchant says a webhook never arrived | `webhook_deliveries`, `webhook_delivery_attempts` | Merchant can redeliver itself via `POST /v1/webhook_events/{id}/redeliver` |
| Who changed what | `audit_log` (append-only) | |
| Ledger check | `ledger_transactions`, `ledger_entries`, `ledger_balances` | Ledger rows are append-only and balanced per asset at commit |

## 2. Operator responsibilities by area

| Area | Responsibility | Today | Planned |
|---|---|---|---|
| Merchants | Onboard, configure, suspend | Internal API (create, settings, keys) | Console with KYB review, suspend/close, limits, fee plans |
| Risk and compliance | KYC/KYB (optional, off by default), AML wallet screening, sanctions | Not built | Toggle KYC per merchant, country or volume; AML screening on every deposit and payout; case management |
| Deposits | Exceptions and recovery | Database | Review queue with approve, refund or compensating entry (two-person) |
| Cashouts and payouts | Approvals above thresholds, stuck transactions | Not built | Approval queue, `needs_review` resolution, re-broadcast of the same signed tx |
| Liquidity | Merchant liquidity health | `GET /v1/liquidity` per merchant key only | Platform-wide view, low-balance alerts, `awaiting_liquidity` queue |
| Wallets and custody | Hot/warm/cold balances, gas tanks, sweeps | Not built (provider not chosen) | Wallet health page, rebalancing proposals with quorum approval |
| Ledger and finance | Reconciliation, fees, reports | Database | Daily reconciliation runs, items, proof of solvency, exports |
| Platform | Chains, assets, configuration, monitoring | Env vars, `assets` table, Prometheus | Asset registry page, alerts, dashboards |
| Audit | Who did what | `audit_log` table | Searchable audit log page, exports, immutable storage |

## 3. Admin console (planned)

Internal web app, reachable only over VPN or SSO, with per-operator identities, mandatory MFA, roles and
two-person approval for anything that moves money or changes limits. Every action is audited with the operator's
identity. It replaces the shared admin token.

### 3.1 Roles

| Role | Can |
|---|---|
| Super admin | Manage operators and roles; everything else needs a second approver |
| Operations | Merchants, settings, keys, deposit and session reviews, webhook support |
| Risk and compliance | KYC/KYB reviews, AML cases, sanctions, freezing merchants or players |
| Finance | Ledger views, reconciliation, fees, reports; approve payouts and adjustments |
| Treasury | Wallet tiers, gas tanks, sweeps, rebalancing proposals (quorum signing at the custody provider) |
| Support (read-only) | Look up merchants, sessions, deposits, webhooks |

### 3.2 Pages

| Page | Shows | Actions |
|---|---|---|
| Overview | Volume per chain and asset, deposits and sessions by status, cashout queue age, `needs_review` counts, chain scan lag, webhook failure rate, hot-wallet levels | Drill into any queue |
| Merchants | List with status, KYC status, volume, liquidity | Create, edit settings (`player_balances`, signing, rate limit, deposit fee, limits), suspend, close, issue keys |
| Merchant detail | Profile, settings, keys (no secrets), webhook endpoints, liquidity per asset, recent activity, audit trail | Same as above, plus require KYB |
| Review queue | Deposits and sessions in `needs_review`, `unsupported` deposits, payouts in `needs_review` | Resolve with a reason; ledger corrections are reversing postings, need a second approver |
| Transactions search | Any deposit, session, payout or ledger transaction by ID, tx hash, address, order ID or player ref | Open detail with status history and ledger postings |
| Approvals | Pending two-person actions: large payouts, payout-address changes, manual adjustments, limit changes | Approve or reject (not your own request) |
| Liquidity | Every merchant's available, allocated and owed per asset; `awaiting_liquidity` cashouts | Notify merchant |
| Wallets | Hot, warm, cold and gas balances per chain against targets; sweep status | Propose warm-to-hot or hot-to-cold transfers (quorum at custody provider) |
| Reconciliation | Daily runs, differences, on-chain vs ledger per asset | Mark items explained, escalate; a shortfall pauses payouts |
| Risk and compliance | KYC/KYB cases, AML alerts on deposits and payout destinations, sanctioned-country blocks | Approve, reject, freeze |
| Assets and chains | Registry with confirmations, enabled and accept-payments flags | Enable or disable an asset or chain (approval required) |
| Audit log | Every operator and merchant action with actor, target and details | Filter, export |
| Operators | Operator accounts and roles | Invite, change role, disable (super admin) |

### 3.3 Example workflows

**Approve a merchant for live.** Operations opens the merchant, checks profile and (if required) KYB is
`verified`, confirms the sandbox integration received `payment_session.completed` webhooks, sets live settings, and
issues a live key. A key with `payouts:write` needs an IP allowlist and a second approver.

**Resolve a `needs_review` deposit.** Operations opens the item from the review queue, compares the transfer on
two explorers with the ledger posting (if any), and chooses: credit (if verification was a false alarm), leave
uncredited and arrange recovery, or post a compensating entry for a credit that should not stand. Finance approves.
The merchant receives the resulting webhook.

**Cashout stuck in `needs_review` (planned).** The outcome on chain is uncertain, so funds stay reserved. Treasury
checks the signed transaction on chain. If it confirmed, mark completed; if it provably cannot confirm, mark failed
(reservation returns to liquidity). Never create a second payout for the same cashout.

**Daily reconciliation (planned).** Finance reviews the run: deposits and payouts on chain against ledger, wallet
balances against liabilities. Any shortfall automatically pauses payouts and pages on-call.
