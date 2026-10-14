-- Phase 2a: request signing, key scopes, IP allowlists, key expiry for rotation, rate limits and the
-- per-merchant choice of who keeps player balances.

ALTER TABLE api_keys
    ADD COLUMN scopes                   TEXT[] NOT NULL
        DEFAULT ARRAY['read', 'deposits:write', 'webhooks:manage', 'keys:manage'],
    -- NULL means any source address.
    ADD COLUMN ip_allowlist             TEXT[],
    -- Set when a key is rolled, so the old key keeps working for an overlap window.
    ADD COLUMN expires_at               TIMESTAMPTZ,
    -- AES-256-GCM under the data encryption key: the server needs the plaintext to verify HMAC signatures.
    -- NULL for keys issued before request signing existed; they cannot sign and are refused where signing is required.
    ADD COLUMN signing_secret_encrypted BYTEA,
    ADD CONSTRAINT api_keys_scopes_known CHECK (
        scopes <@ ARRAY['read', 'deposits:write', 'payouts:write', 'webhooks:manage', 'keys:manage']);

-- Nonces seen on signed requests, kept slightly longer than the accepted timestamp window.
CREATE TABLE request_nonces (
    api_key_id  TEXT        NOT NULL REFERENCES api_keys (id),
    nonce       TEXT        NOT NULL,
    seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (api_key_id, nonce)
);
CREATE INDEX request_nonces_seen_idx ON request_nonces (seen_at);

ALTER TABLE merchants
    -- platform_managed: the gateway keeps a balance per player (default).
    -- merchant_managed: the merchant (for example a game) keeps player balances; deposits fund merchant liquidity only.
    ADD COLUMN player_balances          TEXT    NOT NULL DEFAULT 'platform_managed'
        CHECK (player_balances IN ('platform_managed', 'merchant_managed')),
    -- Signed requests are always required for live keys; this also requires them for test keys.
    ADD COLUMN require_signed_requests  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN rate_limit_per_second    INT     NOT NULL DEFAULT 25 CHECK (rate_limit_per_second BETWEEN 1 AND 10000);
