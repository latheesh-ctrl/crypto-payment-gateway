# Documentation

Status as of 2026-10-14, code on `main` through PR #6 (Phase 2b).

Every page marks what is **live** (built, tested and merged) and what is **planned** (designed, not built yet).
If a page and the code disagree, the code wins; please fix the page.

## Start here

| Page | For | What it covers |
|---|---|---|
| [End-to-end workflows](end-to-end-workflows.md) | Everyone | Who the actors are, how money moves, every flow from onboarding to cashout, with diagrams |
| [API reference](api-reference.md) | Merchant developers, operators | Every exposed endpoint: auth, request, response, errors, webhook events, status values |
| [Merchant workflow](merchant-workflow.md) | Merchants, integration and support teams | The merchant journey from signup to daily operations, and the planned merchant dashboard |
| [Admin workflow](admin-workflow.md) | Platform operators, finance, risk | What operators do today with the internal API, and the planned admin console |

## Deeper references

| Page | What it covers |
|---|---|
| [Merchant authentication](merchant-authentication.md) | API keys, scopes, IP allowlists, request signing with test vector, key rotation |
| [Payment sessions](payment-sessions.md) | Integration guide for gaming merchants: sessions, statuses, webhooks, hosted page, sandbox |
| [Payment flows](payment-flows.md) | Design of deposits, cashouts, merchant liquidity, payouts, cross-chain exchange, ledger postings |
| [Architecture](architecture.md) | Stack, service map, custody, chains, security, scaling and delivery phases |

## What is live and what is planned

| Area | Status | Where |
|---|---|---|
| Merchants, API keys, scopes, IP allowlists, signed requests, rate limits, key rotation | Live | PR #1, #5 |
| Asset registry (Tron, Ethereum, Bitcoin, BNB Smart Chain, Arbitrum) | Live | PR #1 |
| Double-entry ledger, balances, merchant liquidity view | Live | PR #1, #3 |
| End users (players), per-user deposit addresses, merchant pay-in addresses | Live | PR #2, #3 |
| Deposits: detection, confirmations, independent verification, reorg handling, crediting | Live | PR #3, #4 |
| Chain watchers for Tron, EVM chains and Bitcoin | Live | PR #4 |
| Payment sessions (order-bound deposits) and the checkout API | Live | PR #6 |
| Webhooks: endpoints, signed delivery, retries, delivery log, redelivery, secret rotation | Live | PR #2, #6 |
| Idempotency keys, audit log, outbox events | Live | PR #2 |
| Sandbox deposit simulation | Live | PR #3 |
| Player cashouts and merchant payouts, liquidity reservation, signer | Planned (Phase 2c, in progress) | [payment-flows.md](payment-flows.md) §4.2, §6 |
| Hosted payment page (Next.js) | Planned (Phase 2d) | API behind it is live |
| Merchant dashboard | Planned | [merchant-workflow.md](merchant-workflow.md) |
| Admin console with SSO, roles and approvals | Planned | [admin-workflow.md](admin-workflow.md) |
| Reconciliation, reports and CSV exports | Planned (Phase 2e) | |
| Custody provider integration, sweeps, hot/warm/cold wallets | Planned (provider not chosen) | [architecture.md](architecture.md) §4.4 |
| AML screening, optional KYC/KYB module | Planned | [architecture.md](architecture.md) §5 |
| Cross-chain exchange | Planned (later phase) | [payment-flows.md](payment-flows.md) §7 |
