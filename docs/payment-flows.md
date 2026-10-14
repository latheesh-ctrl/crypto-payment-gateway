# Payment Flows: Gap Analysis and Updated Plan

Status: v2 proposal (2026-10-14), updated with latheesh's reference architecture. No existing code has been changed for this. It builds on the Phase 0 scaffold and on [gateway-architecture.md](gateway-architecture.md).

## 0. Guiding principle

The blockchain proves what the wallets hold and which transactions settled. The internal ledger records who owns those funds inside the platform. These are separate systems that are reconciled continuously, and **a database credit never stands in for crypto having been received**. Every credit is backed by a verified on-chain settlement, and every debit to an external address is settled only when the chain confirms it.

## 1. Actors and who owns the money

| Actor | Holds a ledger balance? | Notes |
|---|---|---|
| **Users** (end users: players, customers) | Yes, per asset | Deposit, cash out, exchange. Belong to a merchant. |
| **Merchants** | Yes, kept apart from user funds | Fund liquidity with pay-ins, receive payouts, earn or pay fees |
| **Platform** | Treasury, fee revenue and expense accounts | Never mixed with user or merchant funds |
| **Admins** | No | Approvals, limits, reviews, all audited |

This follows the reference architecture: users have their own internal balances, credited on deposit and reserved on cashout. It replaces the v1 assumption that only merchants hold balances (it answers the open card on where user balances are kept).

Logical architecture: one well-separated modular application first (the existing `gateway-api` with a module per area), extracting services only where load or isolation justifies it. The one module that is isolated from day one is **signing**: it runs separately and the public API can never reach keys.

```
Users · Merchants · Admins  (web, H5, API clients)
  └─ API gateway & authorization: auth, permissions, rate limits, idempotency
      └─ Payment orchestration: deposits · cashouts · merchant pay-ins · merchant payouts · cross-chain exchange
          ├─ Ledger: double-entry journal, balance projections, reservations, audit trail
          └─ Wallet security: address management, signing authorization, transaction policies (isolated)
              └─ Blockchain infrastructure: nodes/RPC, monitoring, confirmations, reconciliation
Supporting: KMS/MPC custody, liquidity monitoring, risk controls, reconciliation, alerting, admin approvals
```

## 2. Comparison with the reference architecture

| Reference item | Our plan before | Now |
|---|---|---|
| Ledger separate from chain truth, reconciled continuously | Yes (daily reconciliation, proof of solvency) | Same; reconciliation also covers provider records |
| Modular monolith first, extract later | Built that way (modules in `gateway-api`) | Same |
| Hot / warm / cold wallets with limits | Yes (architecture 4.4) | Added explicit per-tier limits and approval rules (section 8) |
| MPC/HSM or institutional custody, no keys in code, DB or logs | Yes | Same; signing isolated as its own service |
| Deposit address per user without a key per user | Yes (HD/xpub, MPC vault addresses) | Same |
| A. User deposits | Yes, but credited merchants | Credits the **user's** ledger balance |
| B. User cashouts | Yes, but debited merchants | Reserves and debits the **user's** balance |
| C. Merchant pay-ins and liquidity allocation | **Missing** | Added (section 5) |
| D. Merchant payouts, separate from user cashouts | Partly (merchant settlement) | Added as its own flow (section 6) |
| E. Cross-chain exchange | **Missing** | Added via an audited provider, later phase (section 7) |
| `blockchain_transactions` as its own record | Partly (tx fields on deposits) | Added table shared by all flows |
| Chain-specific finality, reorgs, dropped tx, nonce conflicts, RPC disagreement | Mostly | Added RPC quorum and nonce management (section 9) |
| KYC/AML, licensing, safeguarding | KYC optional by your choice, AML screening on | Unchanged; licensing still needs legal advice before real funds |

## 3. What exists today (Phase 0)

| Area | Built |
|---|---|
| Merchants | `merchants` table, operator create/get API, optional KYC status |
| API keys | HMAC-peppered hashes, `sk_test_` / `sk_live_`, bearer auth |
| Asset registry | `chains`, `assets` seeded with all v1 chains and tokens, `GET /v1/assets` |
| Ledger | Double-entry, append-only (DB triggers), balanced per asset at commit, idempotent by `reference`, row-locked balances, merchant balances can't go negative |
| Balances | `GET /v1/balances` |
| Outbox | Written in the same transaction as postings (relay not built) |
| Tests | 15 green, including concurrency, idempotency, append-only and DB-level balance check |

All changes below are additive. `ledger_transactions` already plays the role of the reference's `ledger_journals`. The ledger's owner types gain `user`, and new account kinds are added. Nothing built needs rewriting.

## 4. User deposits and cashouts

### 4.1 Deposits
1. Assign an approved deposit address for the user, chain and token (permanent per user and chain, or per request).
2. The indexer detects the transfer and identifies tx hash, token contract, recipient and amount. **Native coins and tokens are handled separately**: native transfers from transaction values (and internal calls on EVM), tokens from `Transfer` logs (EVM) or TRC-20 events (Tron), UTXO outputs on Bitcoin.
3. Reject unsupported tokens, lookalike contracts and wrong networks. These are recorded as `unsupported` for manual recovery, never credited.
4. Wait for the chain's finality policy, re-verify against an independent RPC, then credit the user's ledger **exactly once**: debit deposit_wallets, credit user available.
5. Periodically sweep to treasury (hot) wallets. Gas comes from the per-chain gas wallet.

States: `awaiting_payment → detected → confirming → completed`, plus `expired`, `underpaid`, `overpaid`, `reversed` (reorg), `rejected` (AML), `unsupported`. A user sees a pending amount while confirming, but it is not spendable.

### 4.2 Cashouts
1. Authenticate the user (via the merchant integration or user session) and check withdrawal permission, destination format and network, asset and chain.
2. Run risk checks: velocity, limits, destination screening, fraud rules, optional KYC if the merchant or country requires it.
3. Atomically reserve: debit user available, credit user reserved (amount + fee), in the same transaction as inserting the cashout.
4. Submit an authorized instruction to the signing service with policy limits, and a second approval above thresholds.
5. Track tx hash, nonce, gas, replacements and confirmations in `blockchain_transactions`.
6. Settle only on final confirmation: debit user reserved, credit hot wallet / fees / network-fee expense. Release the reservation only after a **definitive** failure or an approved cancellation.

States: `pending → (awaiting_liquidity) → approved → processing → broadcast → completed`, plus `failed`, `cancelled`, `rejected`, `needs_review`. **A broadcast is not a completed cashout**, and an uncertain outcome never triggers a second payout: it goes to `needs_review` with funds still reserved.

### 4.3 Callback authenticity, idempotency and audit (unchanged from v1)
- Chain events come from internal indexers over the private bus only. External provider webhooks at `/hooks/{provider}` are signature-verified, timestamp-checked and deduplicated by provider event id.
- Payloads are never trusted alone: settlement is re-verified on chain before any credit.
- Uniqueness: deposits on `(chain, tx_hash, output_index)`, ledger postings by `reference`, API requests by `Idempotency-Key`, merchant references per merchant.
- Status history tables and an append-only `audit_log` record every transition and admin action.

## 5. Merchant pay-ins and liquidity (decided 2026-10-14)

Rules confirmed by latheesh:
- A merchant's liquidity pays **only its own users' cashouts**. There is no pooling across merchants.
- The merchant earns **nothing** for providing it. Liquidity is simply the pre-funding the platform requires before it will pay out that merchant's users.

Flow:
1. The merchant requests a pay-in and receives a unique funding reference and a deposit address per chain and token.
2. The on-chain transfer is verified exactly like a user deposit (eligible asset, finality, independent re-verification).
3. Ledger: debit deposit_wallets, credit **merchant liquidity** for that asset (a liability to the merchant, never mixed with user funds or platform revenue).
4. When one of the merchant's users requests a cashout, the cashout reserves the user's balance **and** allocates the same amount (plus network fee, if the merchant bears it) from that merchant's available liquidity in the same asset and chain, in one database transaction: debit merchant liquidity, credit merchant liquidity allocated, with a `liquidity_allocations` row linking them.
5. On completion the allocation is consumed. On a definitive failure or cancellation it returns to the merchant's available liquidity.
6. **Not enough liquidity**: the cashout is accepted and reserved but waits in `awaiting_liquidity`. The merchant gets a `liquidity.low` webhook and dashboard alert, and the cashout proceeds automatically, oldest first, once a pay-in tops it up. Merchants set a low-balance alert threshold per asset.

Because liquidity is isolated per merchant and pre-funded, a merchant that fails can never consume another merchant's or the platform's funds. Its users' cashouts can only draw on what that merchant deposited. Withdrawing unused liquidity is a merchant payout (section 6), and amounts already allocated to pending cashouts can't be withdrawn.

## 6. Merchant payouts (new, separate from user cashouts)

1. Calculate the merchant's payable balance under its settlement rules: earned fees or revenue share, unallocated liquidity it may withdraw, minus holds and negative adjustments.
2. Validate amount, asset, chain, whitelisted destination (24 to 48 hour cool-down after changes) and approval requirements (always two-person above a threshold).
3. Reserve (debit merchant payable, credit merchant payout reserved) and submit a unique payout instruction.
4. Track execution on chain, then settle the merchant liability on final confirmation.
5. Reconcile fees, failed transactions, refunds and discrepancies.

It reuses the same signing and transaction-tracking services as user cashouts, but has its own table, states, ledger accounts, limits and approvals, so the business logic and accounting never mix.

## 7. Cross-chain exchange (new, later phase)

1. Accept only configured pairs (source chain/token to destination chain/token).
2. Get a quote from an approved, audited swap, bridge or institutional liquidity provider. Enforce expiry, fees and slippage limits.
3. Reserve the user's source-asset balance and create a unique exchange operation.
4. Execute the source leg and confirm it, then track the destination leg independently.
5. Credit the destination asset only after verified settlement, then reconcile both legs and all fees. Ledger postings balance per asset, using an exchange clearing account per asset, so ETH and USDC are never added together.
6. Partial failures (source settled, destination stuck) go to a recovery workflow with operator tooling. Nothing is assumed reversible.

We will not build our own bridge or trust a bridge message without the provider's settlement evidence.

## 8. Wallets and custody

| Tier | Purpose | Controls |
|---|---|---|
| Hot | Sweeps, approved cashouts and payouts, operational transfers | Holds only expected activity (target 5 to 10% of liabilities per asset); per-transaction and daily limits; automated signing within policy only |
| Warm | Replenishes hot | Stricter authorization, risk checks, multiple approvals (MPC quorum) |
| Cold | Long-term reserves | Offline or highly isolated; manual multi-party signing; not reachable from the payment API |

- Custody via an institutional provider or MPC/HSM signing. Keys and seed phrases never appear in source code, ordinary database rows or logs (OWASP cryptographic storage guidance).
- The signing service is isolated: it only accepts instructions that pass its own policy (destination allowlists, limits, approvals) and it re-checks them independently of the API.
- Key rotation and recovery procedures are documented and rehearsed before mainnet.
- Liquidity monitor: alerts when hot falls below or rises above target, and proposes warm-to-hot or hot-to-cold transfers for approval.

## 9. Blockchain monitoring safeguards
- Chain-specific finality rules (Ethereum finalized checkpoints or a confirmation depth, Tron solidified blocks, Bitcoin depth by amount, Arbitrum L1 batch finality for large amounts), never one fixed count.
- Reorg handling: block hashes tracked; credits not yet final are reversed by compensating entries.
- **RPC quorum**: at least two independent providers; a credit or settlement needs both to agree. Disagreement pauses that chain's processing and alerts.
- RPC outage: indexers resume from a stored cursor, with no gaps.
- Outgoing transactions: nonce manager per hot-wallet address (EVM and Tron), replacement and speed-up tracked as linked rows in `blockchain_transactions`, dropped transactions detected and rebuilt only once the original provably cannot confirm.

## 10. Schema changes (new migration V3, additive)

Mapped from the reference's logical entities to our schema:

| Reference entity | Our table(s) |
|---|---|
| users, merchants | `users` (new: id, merchant_id, external_ref, status, kyc_status, limits profile), existing `merchants` |
| wallet_addresses | `deposit_addresses` (owner type user or merchant, chain, asset context, address, derivation ref, status) |
| blockchain_transactions | `blockchain_transactions` (chain, tx hash, direction, from/to, nonce, block, confirmations, status, gas used, replaced_by) |
| ledger_accounts | existing `ledger_accounts`; owner type gains `user`; new kinds below |
| ledger_journals, ledger_entries | existing `ledger_transactions`, `ledger_entries` |
| deposits | `deposits` |
| cashouts | `cashouts` + `cashout_status_history` |
| merchant_payins | `merchant_payins` |
| merchant_payouts | `merchant_payouts` + status history |
| liquidity_allocations | `liquidity_allocations` (merchant, cashout, asset, amount, status) |
| cross_chain_exchanges | `cross_chain_exchanges` (quote, provider, source leg, destination leg, fees, recovery status) |
| outbox_events, audit_logs | existing `outbox_events`, new `audit_log` |
| (supporting) | `idempotency_keys`, `provider_callbacks`, `limits`, `wallets` (tier, chain, address, limits) |

New ledger account kinds: `USER_AVAILABLE`, `USER_PENDING`, `USER_RESERVED`, `MERCHANT_LIQUIDITY`, `MERCHANT_LIQUIDITY_ALLOCATED`, `MERCHANT_PAYABLE`, `MERCHANT_PAYOUT_RESERVED`, `PLATFORM_WARM_WALLET`, `PLATFORM_EXCHANGE_CLEARING`. User and merchant kinds can never go negative.

## 11. API additions

| Endpoint | Purpose |
|---|---|
| `POST /v1/users`, `GET /v1/users/{id}/balances` | Merchant registers its users; user balances |
| `POST /v1/users/{id}/deposit-addresses`, `GET /v1/deposits` | User deposits |
| `POST /v1/users/{id}/cashouts`, `GET /v1/cashouts/{id}`, `POST /v1/cashouts/{id}/cancel` | User cashouts |
| `POST /v1/payins`, `GET /v1/payins/{id}`, `GET /v1/liquidity` | Merchant pay-ins and liquidity |
| `POST /v1/payouts`, `GET /v1/payouts/{id}` | Merchant payouts |
| `POST /v1/exchange/quotes`, `POST /v1/exchanges` | Cross-chain exchange (later phase) |
| `POST /hooks/{provider}` | Signed provider callbacks |
| `/internal/v1/...` | Approvals, limits, recovery, reconciliation reports |

All `POST` endpoints require `Idempotency-Key`. A user-facing H5 or web app can sit on top of the same API with user sessions later.

## 12. Updated delivery plan

| Phase | Scope |
|---|---|
| 0. Foundations (done, in review) | Repo, CI, merchants, API keys, asset registry, ledger core, balances |
| 1a. Platform plumbing | Users, idempotency keys, audit log, status-history pattern, outbox relay, merchant webhooks, `blockchain_transactions` |
| 1b. Deposits (testnets) | User and merchant pay-in addresses, Tron and EVM indexers, then Bitcoin, finality, reorgs, RPC quorum, sweeps |
| 2. Cashouts and payouts (testnets) | Signing service with policies, reservations, limits, user cashouts, merchant payouts, nonce manager, approvals |
| 3. Liquidity | Merchant liquidity accounts, allocation service and priority policy, liquidity monitor, hot/warm/cold rebalancing |
| 4. Live beta | Mainnet, AML screening, reconciliation jobs, alerting, admin console, pilot merchants, legal sign-off |
| 5. Cross-chain exchange | Provider integration, quotes, two-leg tracking, recovery workflow |

## 13. Open questions
1. ~~How merchant liquidity relates to cashouts~~ Decided: own users only (section 5).
2. ~~What merchants get for liquidity~~ Decided: nothing; it is required pre-funding (section 5).
3. Do user deposits go to the merchant's liquidity automatically (proposed), or stay separate?
4. Custody provider for signing: Fireblocks, Turnkey, Cobo, or self-hosted MPC/HSM.
