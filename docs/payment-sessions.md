# Payment sessions and webhooks: integration guide for gaming merchants

A payment session is one deposit order: your player, your order ID, an amount, an asset and a network. The gateway gives the order its own deposit address and a hosted payment page, watches the chain, and tells your server when the money has really arrived.

Authentication, signing and key management: see [merchant-authentication.md](merchant-authentication.md).

## The flow

1. Your server calls `POST /v1/payment_sessions`.
2. You show the player `payment_url` (redirect or iframe), or show `address` and `amount` in your own UI.
3. The player sends the funds from any wallet.
4. The gateway sees the transfer, waits for the network's confirmations and checks it again on independent providers.
5. Your webhook endpoint receives `payment_session.completed` (or `underpaid` / `overpaid` / `late_payment`). Credit the player then, never before.
6. If in doubt at any time, call `GET /v1/payment_sessions/{id}`.

Never treat the player returning to `return_url`, or anything the browser says, as proof of payment.

## Create a session

```
POST /v1/payment_sessions
Idempotency-Key: 6c1f…                      (plus the signature headers)

{
  "player_ref": "gk_player_8812",            your player ID, up to 128 chars
  "merchant_order_id": "GK-DEP-000123",      unique per merchant; letters, digits . _ : -
  "network": "ARBITRUM",                     ETHEREUM, BSC, ARBITRUM, TRON or BITCOIN
  "asset": "USDT",                           an asset accepted on that network (GET /v1/assets)
  "amount": "25.00",                         decimal string, at most the asset's decimals; never a float
  "expires_in_seconds": 1800,                300 to 86400, default 1800
  "return_url": "https://gameking.example/wallet",   optional, shown as a "back" link on the hosted page
  "metadata": { "table": "vip-3" }           optional, returned as is
}
```

`201 Created` with the session. Sending the same `merchant_order_id` again with the same player, network, asset and amount returns the existing session (`200`); with different details it fails with `409 order_conflict`. A lost response is therefore safe to retry, even with a new idempotency key.

```json
{
  "id": "ps_01JA…",
  "object": "payment_session",
  "status": "open",
  "player_ref": "gk_player_8812",
  "merchant_order_id": "GK-DEP-000123",
  "network": "ARBITRUM",
  "asset": "USDT",
  "asset_id": "USDT_ARBITRUM",
  "amount": "25.000000",
  "amount_received": "0.000000",
  "address": "0x…",
  "required_confirmations": 20,
  "payment_url": "https://pay.example.com/s/ps_01JA…#t=…",
  "return_url": "https://gameking.example/wallet",
  "metadata": { "table": "vip-3" },
  "payments": [],
  "expires_at": "2026-10-14T18:00:00Z",
  "created_at": "…", "updated_at": "…", "completed_at": null
}
```

Other calls:

```
GET  /v1/payment_sessions/{id}
GET  /v1/payment_sessions?merchant_order_id=GK-DEP-000123
GET  /v1/payment_sessions?player_ref=…&status=…&limit=20&starting_after=ps_…
POST /v1/payment_sessions/{id}/cancel        only while open and nothing has arrived
```

## Statuses

| Status | Meaning | Credit the player? |
|---|---|---|
| `open` | Waiting for payment | No |
| `pending` | A transfer was seen on chain, not yet confirmed | No |
| `confirming` | Confirmations are accumulating | No |
| `completed` | Exactly `amount` arrived and was verified | Yes, `amount_received` |
| `underpaid` | Less than `amount` arrived (a top-up can still complete it) | Your policy; the money is yours either way |
| `overpaid` | More than `amount` arrived | Yes, `amount_received` |
| `late_payment` | Paid after `expires_at` | Your policy; the money is in your liquidity |
| `expired` | Nothing arrived in time (a later payment turns it into `late_payment`) | No |
| `cancelled` | Cancelled by you before any payment | No |
| `needs_review` | Wrong asset sent, or verification disagreed; operations will contact you | No |

Confirmed payments go to your merchant liquidity (minus your deposit fee). The gateway does not keep a balance per player: your game wallet is the player's balance.

`amount_received` is cumulative. If a session gets more money later (an underpayment topped up, or an overpayment), credit the difference between `amount_received` and what you already credited for that session.

## Webhooks

Register an endpoint with `POST /v1/webhook_endpoints` (`{"url": "...", "event_types": ["*"]}` or a list of event types). Events for sessions:

`payment_session.pending`, `.confirming`, `.completed`, `.underpaid`, `.overpaid`, `.late_payment`, `.expired`, `.cancelled`, `.needs_review`, `.reversed` (a reorganisation removed an unconfirmed payment; the session is back to `open` or `expired`).

Each delivery:

```
POST <your url>
Content-Type: application/json
CPG-Event-Id: evt_01JA…
CPG-Signature: t=1760464800,v1=5f2c…

{ "id": "evt_01JA…", "type": "payment_session.completed", "created_at": "…", "livemode": false,
  "data": { …the session, same shape as the API… } }
```

Your endpoint must:

1. **Verify the signature** over the raw body: `HMAC-SHA256(endpoint_secret, t + "." + raw_body)` in hex equals one of the `v1` values. During a secret rotation there are two `v1` values; accept either.
2. **Reject old timestamps**: refuse if `t` is more than 5 minutes from your clock.
3. **Deduplicate on `id`**: delivery is at least once, so store event IDs and ignore repeats.
4. **Credit idempotently**: key the credit on the session `id`; never credit the same session twice.
5. **Answer 2xx quickly** and do slow work afterwards. Anything else is retried with backoff (10 s doubling, up to 6 h, 16 attempts).
6. **Don't rely on order**: events can arrive out of order. Use the session's `status` and `amount_received` in the payload, or fetch the session.

Delivery log and recovery:

```
GET  /v1/webhook_events?type=payment_session.completed
GET  /v1/webhook_events/{id}                 the event, each delivery and every attempt (status code, error, duration)
POST /v1/webhook_events/{id}/redeliver       send it again now
POST /v1/webhook_endpoints/{id}/rotate_secret   {"overlap_hours": 24}; both secrets sign during the overlap
```

## Hosted payment page

`payment_url` opens the gateway's page for this session (the page itself ships in the next step; the API behind it is live). It shows the network, asset, amount, address (with QR code), confirmations and the time left. The token after `#t=` is the only key to that page; it never reaches your server logs because it is in the URL fragment. The page reads `GET /checkout/v1/sessions/{id}` with the header `X-Checkout-Token`, which returns no player, order or merchant data.

## Testing without real funds

In test mode, pay a session without any chain:

```
POST /v1/sandbox/simulate_deposit
{ "address": "<session address>", "asset": "USDT_ARBITRUM", "amount": "25" }
```

The session completes and the webhooks fire as they would in production.
