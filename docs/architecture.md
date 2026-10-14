# Crypto Payment Gateway: Architecture and Delivery Plan

Status: draft v1 (2026-10-14). Items marked **[DECISION]** depend on the open questions at the end.

## 1. What we are building

A multi-merchant gateway, in the mould of Stripe for crypto: merchants sign up, get API keys, create payment intents, send customers to a hosted checkout (or embed it), and receive signed webhooks when payments confirm. The platform watches blockchains, credits merchant balances in a double-entry ledger, takes a fee, and settles to merchants.

Design goals: correctness of money above all (idempotent, auditable, never double-credit), horizontal scale per chain, strict tenant isolation, and a professional merchant experience (docs, SDKs, sandbox, dashboard).

## 2. Default stack

| Layer | Choice | Why |
|---|---|---|
| Core services | Java 21, Spring Boot 4.1 (Spring MVC on virtual threads, Spring Security, JdbcClient, Flyway) | Chosen by the team (2026-10-14); mature for payments, strong transactions and tooling |
| Chain indexers | Java 21 Spring Boot workers using virtual threads; web3j (Ethereum, BSC, Arbitrum), Trident SDK (Tron), bitcoinj or Bitcoin Core RPC (BTC) | One language across backend; virtual threads handle many concurrent RPC calls cheaply |
| Database | PostgreSQL 16 (one cluster, schema-per-domain, migrations with Flyway) | ACID ledger, row-level security for tenants |
| Cache / rate limits / locks | Redis | API rate limiting, idempotency key cache, short-lived quotes |
| Event bus | NATS JetStream (Kafka if volume demands later) | Durable, replayable events between services, simpler to run than Kafka |
| Frontends | React + TypeScript with Next.js, Tailwind CSS and shadcn/ui; TanStack Query for data | Recommended: polished, fast hosted checkout (server-rendered), one design system for checkout, merchant dashboard and admin console |
| Key custody | MPC wallet provider (Fireblocks, Turnkey or Cobo); self-hosted HSM as a later option | Platform is custodial; never hold raw private keys in app servers |
| Node access | Managed RPC (Alchemy, QuickNode, TronGrid) plus our own nodes for primary chains later | Fast start, redundancy via multiple providers |
| Infra | AWS, Kubernetes (EKS), Terraform, GitHub Actions | Standard, auditable, region choice for compliance |
| Observability | OpenTelemetry, Prometheus, Grafana, Loki, Sentry | Tracing across API, bus and indexers |
| Secrets | AWS KMS + Secrets Manager | Envelope encryption for API secrets and webhook keys |

Repo layout: one monorepo started from scratch. Backend is a Gradle (Kotlin DSL) multi-module build: `common`, `merchant-service`, `payment-service`, `ledger-service`, `wallet-service`, `indexer-evm`, `indexer-tron`, `indexer-btc`, `webhook-service`, `payout-service`, `api-gateway` (Spring Cloud Gateway). Frontends under `/web` (pnpm workspace: `checkout`, `merchant-dashboard`, `admin`). Testcontainers for Postgres/Redis/NATS in integration tests.

## 3. Service map

```
 Merchant server ──API──▶ API Gateway (auth, rate limit, idempotency)
                               │
      ┌──────────────┬─────────┼───────────┬──────────────┐
      ▼              ▼         ▼           ▼              ▼
  Merchant svc   Payments svc  Pricing   Webhook svc   Payout svc
  (onboarding,   (intents,     (FX quotes, (signed       (settlement,
   keys, KYB)     addresses,    rate lock)  delivery,     withdrawals)
      │           state mach.)              retries)          │
      │              │                         ▲              │
      ▼              ▼                         │              ▼
   Postgres ◀── Ledger svc ◀── NATS events ◀── Chain indexers (per chain, Java)
                (double entry)                  │
                                                ▼
                                       Wallet / Custody svc ─▶ MPC/HSM
 Customer ──▶ Hosted checkout (Next.js) ──▶ Payments svc (public, read-only)
 Merchant ──▶ Merchant dashboard        Ops ──▶ Admin console (RBAC, 2FA, audit)
```

## 4. Domains in detail

### 4.1 Merchant onboarding and API keys
- Sign up, email verification, mandatory 2FA (TOTP / passkeys), team members with roles (owner, admin, developer, finance, read-only).
- Verification is optional (decided 2026-10-14). Merchants go live after email + 2FA by default. A pluggable KYC/KYB module (Sumsub, Onfido or Persona adapter) is built in but switched off, and can be required per merchant, per country or above a volume threshold from the admin console without code changes. Sanctioned-country blocking (by IP and declared country) stays on.
- API keys: publishable (`pk_live_…`) and secret (`sk_live_…`) per environment; secrets shown once, stored as salted hash; per-key scopes, IP allowlists, rotation, last-used tracking.
- Optional request signing (HMAC) for high-value merchants.

### 4.2 Payment intents and hosted checkout
- `POST /v1/payment_intents` with amount in a crypto asset (optionally a fiat-denominated price converted to crypto at a locked rate, for display only; no fiat ever moves), order id, metadata, redirect URLs, expiry. Requires `Idempotency-Key`.
- Customer picks asset/network at checkout (or merchant fixes it); Pricing svc locks a quote for 15 minutes.
- Unique deposit address per intent (HD derivation or MPC vault address) so payments map to intents without memos. Tron/EVM addresses are reused via a pool after expiry plus cool-down.
- State machine: `requires_payment → pending (seen in mempool/block) → confirming → succeeded`, with `underpaid`, `overpaid`, `expired`, `late_payment`, `refunded`. Under/overpayment tolerances configurable per merchant.
- Hosted checkout: branded per merchant (logo, colours, custom domain later), QR code, wallet deep links (WalletConnect for EVM), live status via SSE, localisation.
- Also: payment links and invoices (no code needed), plus a JS embed and plugins (WooCommerce, Shopify) later.

### 4.3 Chains and tokens (decided 2026-10-14)

| Chain | Model | Assets in v1 | Default confirmations |
|---|---|---|---|
| Tron (TRC-20) | Account | TRX, USDT | 19 (solidified block) |
| Ethereum (ERC-20) | Account | ETH, USDT, USDC | 12 |
| Bitcoin | UTXO | BTC | 2 small / 6 large |
| BNB Smart Chain (BEP-20) | Account, EVM | USDT (BNB held only as gas) | 15 |
| Arbitrum One | Account, EVM L2 | ETH, USDC, USDT | finalised on L1, or ~20 L2 blocks for small amounts |

Adding chains and tokens later must need no code change for a new token on an existing chain, and only a new plugin for a new chain:
- **Asset registry** table: chain, symbol, contract address, decimals, min amount, confirmation rules, enabled flag. New ERC-20/TRC-20/BEP-20 tokens are a row plus an admin toggle.
- **Chain plugin interface**: `deriveAddress`, `scanBlock`, `getConfirmations`, `buildTransfer`, `estimateFee`. Three plugin families cover v1: EVM (Ethereum, BSC, Arbitrum share one indexer codebase, configured per chain), Tron, and Bitcoin (UTXO).
- Bitcoin specifics: per-intent addresses from an xpub (native SegWit, BIP84), gap-limit management, RBF/unconfirmed handling, and UTXO consolidation into the platform wallets.
- Gas: token sweeps need TRX energy/bandwidth on Tron and ETH/BNB for gas on EVM chains, managed from a gas-tank wallet per chain.
- Likely next chains: Polygon, Base, Solana, Litecoin.

### 4.4 Wallets and custody (decided 2026-10-14: custodial)
The platform holds merchant funds in its own wallets and pays merchants out in the same crypto they were paid in. No banking or fiat is involved.
- **Flow**: customer pays to a per-intent deposit address, the indexer confirms it, the ledger credits the merchant's balance for that asset, a sweeper moves funds into the omnibus hot wallet for that chain, and payouts leave from the hot wallet.
- **Wallet tiers per chain**: deposit addresses, hot wallet (small float for payouts, auto-refilled), warm wallet (MPC with policy approvals), cold storage (offline, manual multi-party approval). Target roughly 5-10% of balances hot.
- **Signing**: MPC provider (Fireblocks, Turnkey or Cobo) for v1 so no single machine ever holds a full key; transaction policies enforced at the provider (destination whitelists, amount limits, quorum approvals). Custody service is the only component that can request signatures, from an isolated network segment.
- **Gas tanks**: per-chain gas wallets fund token sweeps (TRX energy/bandwidth on Tron, ETH on Ethereum and Arbitrum, BNB on BSC), with alerts when low. Sweeps run on a threshold or schedule to keep fees down.
- **Proof of solvency**: daily reconciliation of on-chain wallet balances against ledger liabilities per asset; any shortfall pages on-call and pauses payouts.
- **Withdrawal controls**: merchant payout addresses whitelisted with a 24-48h cool-down after changes, velocity limits, two-person approval above thresholds, and AML screening on every destination.

### 4.5 Blockchain monitoring and confirmations
- One indexer deployment per chain, reading from 2+ RPC providers, persisting a cursor (last processed block) for exact resume.
- Watches only addresses we own (Bloom filter / set in Redis) for native transfers and token `Transfer` logs.
- Reorg handling: track block hashes, roll back unconfirmed credits on reorg. Confirmation thresholds per chain and per amount (e.g. ETH 12, Tron 19, BTC 2 small / 6 large).
- Emits `chain.tx.detected`, `chain.tx.confirmed`, `chain.tx.reorged` to NATS; Payments svc is the only consumer that changes intent state.
- Reconciliation job compares on-chain balances to ledger daily and pages on drift.

### 4.6 Webhooks
- Events: `payment_intent.*`, `payout.*`, `refund.*`. Payload signed with HMAC-SHA256 and timestamp (Stripe-style `Signature` header), per-endpoint secret.
- At-least-once delivery, exponential backoff for 3 days, dead-letter, manual replay from dashboard, delivery logs with response bodies.
- Events API (`GET /v1/events`) so merchants can poll as a fallback.

### 4.7 Ledger
- Double-entry, append-only: every movement is a transaction with balanced postings across accounts (merchant available, merchant pending, platform fees, hot wallet, gas expense, etc.), per asset, amounts as integers in smallest units.
- Balances derived from postings (with snapshot tables for speed); no updates or deletes.
- Every posting references its source (tx hash, payout id) so it is fully auditable.

### 4.8 Fees and pricing
- Fee plans per merchant: percentage + fixed, per asset, volume tiers, who pays network fees (merchant or customer).
- FX rates from 2+ sources (e.g. Kraken, Coinbase, CoinGecko) with median and staleness checks; quote locked per intent.

### 4.9 Settlement and payouts
- Merchants are paid in the same asset and chain they received (USDT on Tron stays USDT on Tron). No conversion, no fiat.
- Auto-settlement schedule (daily/weekly, or above a balance threshold) or on-demand withdrawals to whitelisted addresses.
- Network fee for the payout is either deducted from the payout or charged as a flat fee per chain, set in the merchant's fee plan.
- Payout batching to cut gas, approval rules, travel-rule data for VASP-to-VASP transfers.
- Refunds: to a customer-supplied address, debited from merchant balance.

### 4.10 Dashboards
- Merchant: payments, balances, payouts, refunds, webhooks and logs, API keys, team, branding, reports/CSV export, sandbox toggle.
- Admin: merchant review (KYB), risk flags, transaction search, manual adjustments (dual approval), wallet health and gas balances, fee plan management, full audit log. Admin access over VPN/SSO only.

### 4.11 Developer experience
- OpenAPI 3.1 spec as source of truth; springdoc-openapi to publish it; generated SDKs (Java, Node, Python, PHP); docs site with guides; sandbox on testnets (Sepolia, Shasta, Amoy) with a "simulate payment" button.

## 5. Security and compliance
- Markets: global (decided 2026-10-14). Licensing is a legal question outside this plan: a custodial gateway serving merchants worldwide is treated as a VASP/MSB in many places (e.g. EU MiCA, US FinCEN, UAE VARA). Pick an operating entity and jurisdiction with counsel before mainnet. The design supports per-country rules (geo-blocking, KYC thresholds) so restrictions can be added without rework.
- AML: kept on even with KYC optional, because it protects the platform's own wallets. USDT and USDC issuers freeze addresses that touch sanctioned funds, which could lock the hot wallet. Wallet screening on every incoming payment and payout (Chainalysis, TRM or Elliptic), sanctions lists, transaction monitoring rules, case management in admin, Travel Rule (Notabene/Sumsub) for payouts to other VASPs.
- Platform security: SOC 2 path, OWASP ASVS, pen tests before launch, WAF + DDoS protection (Cloudflare), least-privilege IAM, encrypted at rest and in transit, audit logs immutable (S3 Object Lock), bug bounty after launch.

## 6. Scalability and reliability
- Stateless API pods behind a load balancer, autoscaled; indexers scale per chain.
- Postgres with read replicas; partition high-volume tables (events, webhook deliveries, chain txs) by month; ledger stays on primary.
- Outbox pattern for every DB change that emits an event, so state and events never diverge.
- Idempotency everywhere: API, event consumers (dedupe by event id), on-chain credits (unique on chain + tx hash + log index).
- Targets: 99.95% API availability, p99 API < 300 ms, payment detected within one block of inclusion.
- Multi-AZ from day one; multi-region DR (warm standby) before scale-up.

## 7. Delivery phases

Deposits and cashouts are specified in detail, with an updated phase plan, in [payment-flows.md](payment-flows.md).


| Phase | Scope |
|---|---|
| 0. Foundations | Monorepo, CI/CD, infra as code, auth, merchant accounts, API keys, OpenAPI skeleton, ledger core |
| 1. MVP (sandbox) | Payment intents, hosted checkout, Tron, EVM (Ethereum, BSC, Arbitrum) and Bitcoin indexers on testnets, webhooks, merchant dashboard v1 |
| 2. Live beta | Custody integration, mainnet, fees, wallet AML screening, optional KYC module (off by default), admin console, a few pilot merchants |
| 3. Payouts and scale | Settlement, refunds, payment links/invoices, reporting, SDKs, plugins |
| 4. Expansion | More chains, optional cross-asset conversion, custom checkout domains, multi-region, SOC 2 |

## 8. Open decisions
1. ~~Custodial vs non-custodial~~ Decided: custodial, payouts in the same crypto, no fiat. See 4.4 and 4.9.
2. ~~Which chains/tokens first~~ Decided: see 4.3.
3. Where the code lives (GitHub repo to attach).
4. ~~Target markets~~ Decided: global, KYC optional (toggle per merchant/country). See 4.1 and 5.
