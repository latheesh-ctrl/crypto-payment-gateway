# Payouts (cashouts): integration guide for gaming merchants

A payout sends crypto from your liquidity on the gateway to a player's wallet, in the same asset and on the same network you choose. It is for merchants that keep their players' balances themselves (`player_balances = merchant_managed`, e.g. a game wallet): you debit the player in your system, then ask the gateway to pay. Merchants whose player balances the gateway keeps will use user cashouts instead (not in this API yet) and get `409 payouts_require_merchant_managed_balances`.

Authentication, signing and key management: see [merchant-authentication.md](merchant-authentication.md). Payout requests need an API key with the `payouts:write` scope; a live key with that scope must have an IP allowlist, and live requests must be signed. Payout routes get a fifth of the key's normal rate limit.

## The flow

1. The player asks to withdraw. Your server reserves the amount in the player's game wallet.
2. Your server calls `POST /v1/payouts` with your own withdrawal ID as `merchant_payout_id`.
3. The gateway reserves `amount + fee` from your liquidity in the same database transaction that checks the balance. If your liquidity is short, the payout waits in `awaiting_liquidity` and is reserved automatically, oldest first, when funds arrive (a deposit or a top-up).
4. A worker signs exactly one transaction for the payout, stores it, then broadcasts it.
5. The gateway follows the transaction to the network's finality and sends you `payout.completed` with the `tx_hash`. Finalise the withdrawal in your wallet then.
6. On `payout.failed` or `payout.cancelled` nothing left the platform: your liquidity is refunded and you should return the amount to the player's game balance.

## Create a payout

```
POST /v1/payouts
Idempotency-Key: 3f9a…                       (plus the signature headers)

{
  "merchant_payout_id": "GK-WD-000981",      unique per merchant; letters, digits . _ : -
  "player_ref": "gk_player_8812",            your player ID, up to 128 chars, kept for audit
  "network": "ARBITRUM",                     ETHEREUM, BSC or ARBITRUM today
  "asset": "USDC",
  "amount": "25.00",                         what the player receives; decimal string, never a float
  "destination_address": "0xAbC…",           the player's wallet on that network
  "metadata": { "withdrawal": "W-1" }        optional, returned as is
}
```

`201 Created` with the payout. Sending the same `merchant_payout_id` again with the same player, network, asset, amount and destination returns the existing payout (`200`); with different details it fails with `409 payout_conflict`. A lost response is therefore safe to retry, even with a new idempotency key, and can never pay twice.

```json
{
  "id": "po_01JA…",
  "object": "payout",
  "status": "processing",
  "status_reason": null,
  "merchant_payout_id": "GK-WD-000981",
  "player_ref": "gk_player_8812",
  "network": "ARBITRUM",
  "asset": "USDC",
  "asset_id": "USDC_ARBITRUM",
  "amount": "25.000000",
  "fee": "0.250000",
  "total_debited": "25.250000",
  "destination_address": "0xAbC…",
  "tx_hash": null,
  "confirmations": 0,
  "required_confirmations": 20,
  "metadata": { "withdrawal": "W-1" },
  "created_at": "…", "updated_at": "…", "completed_at": null
}
```

Errors:

| Status | `code` | Meaning |
|---|---|---|
| 400 | `invalid_destination_address` | Not a valid address for the network, the zero address, or one of the platform's own wallets |
| 400 | `invalid_amount` | Not positive, or more decimals than the asset has |
| 400 | `unsupported_asset` | The asset is not enabled on that network |
| 403 | `forbidden` | The key lacks the `payouts:write` scope |
| 409 | `payout_conflict` | `merchant_payout_id` reused with different details |
| 409 | `payouts_require_merchant_managed_balances` | Your account is not set up for merchant-kept player balances |
| 422 | `payout_limit_exceeded` | Above your per-payout or rolling 24-hour limit for that asset |
| 503 | `payouts_unavailable` | Payouts on that network are not enabled yet |

## Look up, list, cancel

- `GET /v1/payouts/{id}`
- `GET /v1/payouts?merchant_payout_id=GK-WD-000981` (also `player_ref`, `status`, `starting_after`, `limit`)
- `POST /v1/payouts/{id}/cancel`: only while `awaiting_liquidity` or `processing` (before the transaction is signed). Releases the reservation. Afterwards: `409 payout_not_cancellable`.

## Statuses and webhooks

Each change sends a webhook named `payout.<status>` whose `data` is the payout object above.

| Status | Meaning | Your action |
|---|---|---|
| `awaiting_liquidity` | Not enough liquidity yet; reserved automatically later | Top up, or cancel |
| `processing` | Liquidity reserved; transaction about to be signed | Wait |
| `broadcast` | Transaction sent to the network (`tx_hash` set) | Wait; you may show the hash to the player |
| `confirming` | In a block, counting confirmations | Wait |
| `completed` | Final on chain | Finalise the withdrawal |
| `failed` | The transaction reverted, or provably can never be mined; nothing reached the player; liquidity refunded | Return the amount to the player |
| `cancelled` | Cancelled before signing; liquidity refunded | Return the amount to the player |
| `needs_review` | Outcome uncertain (see below); still being tracked | Do nothing yet; it will end in `completed` or `failed` |

A payout can go from `confirming` back to `broadcast` if a chain reorganisation drops its block; the same transaction is re-sent and normally completes. Only `completed`, `failed` and `cancelled` are final.

## How the gateway avoids paying twice

- **One transaction per payout.** The signed bytes are stored (unique per payout) before anything is broadcast. Retries, restarts and "not found" answers only ever re-send those exact bytes; the network mines a transaction at most once.
- **Nonces.** Each hot wallet's nonce is allocated under a database row lock, so concurrent workers never reuse one. If something else used the wallet, the node's pending nonce wins.
- **Uncertain outcomes go to review, not to a retry.** If the transaction is not mined within `stuck-after` (default 30 minutes), cannot be broadcast, or its nonce was used by a different transaction, the payout becomes `needs_review`. It keeps being tracked and completes on its own if the transaction is mined. An operator can mark it `failed` (refunding your liquidity) only when the chain proves the transaction can never be mined: the node does not know it and the wallet's mined nonce has passed it. The gateway checks that proof itself and refuses otherwise.
- **Leases.** Workers lease payouts for a short time (`FOR UPDATE SKIP LOCKED`), so each payout is advanced by one worker at a time across any number of instances.

## Money

| Step | Ledger posting (all amounts in the payout's asset unless noted) |
|---|---|
| Reserve | Dr merchant `liquidity` / Cr merchant `liquidity_allocated` (amount + fee) |
| Complete | Dr merchant `liquidity_allocated` (amount + fee) / Cr platform `hot_wallet` (amount) / Cr platform `fees` (fee) |
| Network fee | Dr platform `network_fee_expense` / Cr platform `hot_wallet`, in the chain's native coin; paid by the platform |
| Fail or cancel | Dr merchant `liquidity_allocated` / Cr merchant `liquidity` (amount + fee) |

`GET /v1/liquidity` shows your available liquidity (`available`) and the amount reserved for payouts in flight (`allocated`).

## Operator controls

- `PATCH /internal/v1/merchants/{id}/settings` with `player_balances`, `payout_fee_bps`, `deposit_fee_bps`.
- `PUT /internal/v1/merchants/{id}/payout_limits/{asset_id}` with `per_payout` and/or `per_day` (decimal strings); `DELETE` removes them.
- `GET /internal/v1/payouts/{id}`: the payout with its status history and transaction (hash, nonce, broadcasts).
- `POST /internal/v1/payouts/{id}/resolve_failed`: fails a payout in review whose transaction provably can never be mined.

## Signing and custody

Transactions are legacy EIP-155 transactions built and signed by the gateway (native transfers and ERC-20 `transfer`), checked against the EIP-155 reference vector in the tests. Signing goes through a `TransactionSigner` port. The only signer today is `dev_key`, a private key held in memory for local development and public test networks; a live deployment refuses to start with it. Live payouts need the custody provider (MPC or HSM) signer, which plugs into the same port. Tron and Bitcoin payouts follow with that integration.

Before signing, the gateway checks that the hot wallet can cover the transfer and gas, and waits instead of signing while the network gas price is above the configured cap, so a nonce is never spent on a transaction that cannot be mined.

## Testing without real funds

All tests run against an in-memory chain with real transaction signing (`FakePayoutRail`) and a local JSON-RPC stub (`EvmPayoutRailTest`). For a manual end-to-end run, point `PAYOUTS_<CHAIN>_RPC_URL` at a public testnet or a local devnet and use a throwaway `dev_key` funded with test coins only.
