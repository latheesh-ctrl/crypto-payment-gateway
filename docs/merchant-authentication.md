# Merchant API authentication

Every call to `/v1/**` is server to server. Never call the API from a browser or a game client.

## Keys

A key has two secrets, both shown once when the key is created:

| Secret | Looks like | Used for |
|---|---|---|
| API key | `sk_test_…` / `sk_live_…` | `Authorization: Bearer <key>` on every request |
| Signing secret | `ss_…` | HMAC signature on every request (required for live keys) |

Store both in your secret manager. The gateway keeps only a hash of the API key and an encrypted copy of the signing secret.

### Scopes

| Scope | Allows |
|---|---|
| `read` | every `GET` |
| `deposits:write` | creating players, deposit addresses and (soon) payment sessions |
| `payouts:write` | creating cashouts. Never on by default; live keys with it must have an IP allowlist |
| `webhooks:manage` | webhook endpoints |
| `keys:manage` | creating, rolling and revoking keys |

New keys get every scope except `payouts:write`. A key can only create keys with scopes it has itself.

### IP allowlist

`ip_allowlist` takes addresses or CIDR ranges (`"203.0.113.10"`, `"10.0.0.0/8"`). Requests from anywhere else get `403 ip_not_allowed`.

### Rotation without downtime

1. `POST /v1/api_keys/{id}/roll` with `{"overlap_hours": 24}` (0 to 168). The response has the new key and signing secret.
2. Deploy the new secrets. Both keys work during the overlap.
3. The old key stops at the end of the overlap, or immediately with `POST /v1/api_keys/{id}/revoke`.

`GET /v1/api_keys` lists keys (never secrets) with `last_used_at`, so you can confirm the old key is no longer used.

## Signing requests

Send three headers with every request:

```
CPG-Timestamp: 1760464800                 Unix seconds, within 300 s of the gateway's clock
CPG-Nonce: 6f1c0b8e9a7d4c2bb1e5f3a9d8c7b6a5   16-128 chars of A-Z a-z 0-9 _ -, never reused
CPG-Signature: v1=<hex HMAC-SHA256(signing_secret, canonical)>
```

```
canonical = timestamp + "\n" + nonce + "\n" + METHOD + "\n" + path_and_query + "\n" + hex(SHA-256(raw_body))
```

- `path_and_query` is exactly what you send, for example `/v1/users?limit=5`.
- Hash the exact bytes you send as the body. An empty body hashes to `e3b0c442…b855`.
- Signatures are always required on live keys. Test keys accept unsigned requests unless the merchant setting `require_signed_requests` is on, so you can build up the integration step by step. A signature that is sent is always checked.

Test vector: secret `ss_test_vector_secret`, timestamp `1760464800`, nonce `nonce-0000000000001`, `POST /v1/users?x=1`, body `{"player_ref":"p1"}` gives
`v1=30222a073cfafff095d00d1a19f9e4a8cb9db12fb2c6525ceee2753d397c461a`.

Node.js:

```js
import crypto from "node:crypto";

function sign(secret, method, pathAndQuery, body = "") {
  const ts = Math.floor(Date.now() / 1000).toString();
  const nonce = crypto.randomBytes(16).toString("hex");
  const bodyHash = crypto.createHash("sha256").update(body).digest("hex");
  const canonical = [ts, nonce, method.toUpperCase(), pathAndQuery, bodyHash].join("\n");
  const sig = crypto.createHmac("sha256", secret).update(canonical).digest("hex");
  return { "CPG-Timestamp": ts, "CPG-Nonce": nonce, "CPG-Signature": `v1=${sig}` };
}
```

## Retries

Every `POST` also needs an `Idempotency-Key`. Retrying with the same key and body returns the original response (`Idempotent-Replayed: true`). A retry is a new HTTP request, so it needs a fresh nonce, timestamp and signature, but the same `Idempotency-Key`.

## Errors

| Status | `code` | Meaning |
|---|---|---|
| 401 | `unauthenticated` | Missing, unknown, expired or revoked API key |
| 401 | `signature_required` | This key must sign requests |
| 401 | `signature_unavailable` | Key issued before signing existed; roll it |
| 401 | `invalid_timestamp`, `timestamp_out_of_range` | Bad or stale `CPG-Timestamp` |
| 401 | `invalid_nonce`, `nonce_reused` | Bad or repeated `CPG-Nonce` |
| 401 | `invalid_signature` | Signature does not match |
| 403 | `ip_not_allowed` | Source address not on the key's allowlist |
| 403 | `forbidden` | The key lacks the scope for this route |
| 429 | `rate_limited` | Slow down; wait `Retry-After` seconds |

Rate limits apply per key: the merchant's sustained rate (default 25 requests/s) with bursts up to twice that; cashout routes get a fifth of it.
