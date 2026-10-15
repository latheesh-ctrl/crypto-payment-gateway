-- Phase 2c: cashouts (payouts) paid from merchant liquidity, with their outbound blockchain transactions.

ALTER TABLE merchants ADD COLUMN payout_fee_bps INT NOT NULL DEFAULT 0 CHECK (payout_fee_bps BETWEEN 0 AND 10000);

-- Optional per-asset limits; no row means no limit beyond available liquidity.
CREATE TABLE merchant_payout_limits (
    merchant_id  TEXT          NOT NULL REFERENCES merchants (id),
    asset_id     TEXT          NOT NULL REFERENCES assets (id),
    per_payout   NUMERIC(78,0) CHECK (per_payout > 0),
    per_day      NUMERIC(78,0) CHECK (per_day > 0),
    PRIMARY KEY (merchant_id, asset_id)
);

CREATE TABLE payouts (
    id                       TEXT PRIMARY KEY,
    merchant_id              TEXT          NOT NULL REFERENCES merchants (id),
    player_ref               TEXT          NOT NULL,
    merchant_payout_id       TEXT          NOT NULL,
    chain_code               TEXT          NOT NULL REFERENCES chains (code),
    asset_id                 TEXT          NOT NULL REFERENCES assets (id),
    destination_address      TEXT          NOT NULL,
    amount                   NUMERIC(78,0) NOT NULL CHECK (amount > 0),
    fee                      NUMERIC(78,0) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    status                   TEXT          NOT NULL CHECK (status IN
                                 ('awaiting_liquidity', 'processing', 'signing', 'broadcast', 'confirming',
                                  'completed', 'failed', 'cancelled', 'needs_review')),
    status_reason            TEXT,
    tx_hash                  TEXT,
    block_number             BIGINT,
    confirmations            INT           NOT NULL DEFAULT 0,
    required_confirmations   INT           NOT NULL,
    reserved                 BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Worker scheduling: the next time a worker should look at this payout, and a lease so only one does.
    next_attempt_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    locked_until             TIMESTAMPTZ,
    attempts                 INT           NOT NULL DEFAULT 0,
    metadata                 JSONB         NOT NULL DEFAULT '{}',
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at             TIMESTAMPTZ,
    UNIQUE (merchant_id, merchant_payout_id)
);
CREATE INDEX payouts_merchant_idx ON payouts (merchant_id, id DESC);
CREATE INDEX payouts_work_idx ON payouts (next_attempt_at)
    WHERE status IN ('processing', 'signing', 'broadcast', 'confirming', 'needs_review');
CREATE INDEX payouts_awaiting_idx ON payouts (created_at) WHERE status = 'awaiting_liquidity';

CREATE TABLE payout_status_history (
    id           BIGSERIAL PRIMARY KEY,
    payout_id    TEXT        NOT NULL REFERENCES payouts (id),
    from_status  TEXT,
    to_status    TEXT        NOT NULL,
    reason       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payout_status_history_idx ON payout_status_history (payout_id, id);
CREATE TRIGGER payout_status_history_append_only
    BEFORE UPDATE OR DELETE ON payout_status_history
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();

-- Exactly one signed transaction per payout, stored before it is ever broadcast. An uncertain outcome is
-- resolved by re-broadcasting these same bytes, never by signing a second transfer.
CREATE TABLE outbound_transactions (
    id                 TEXT PRIMARY KEY,
    payout_id          TEXT        NOT NULL UNIQUE REFERENCES payouts (id),
    chain_code         TEXT        NOT NULL REFERENCES chains (code),
    from_address       TEXT        NOT NULL,
    nonce              BIGINT,
    tx_hash            TEXT        NOT NULL,
    raw_tx             TEXT        NOT NULL,
    broadcast_count    INT         NOT NULL DEFAULT 0,
    first_broadcast_at TIMESTAMPTZ,
    last_broadcast_at  TIMESTAMPTZ,
    last_error         TEXT,
    network_fee        NUMERIC(78,0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (chain_code, tx_hash)
);

-- Next nonce per hot wallet (account-based chains); the row lock serialises signing per wallet.
CREATE TABLE hot_wallet_nonces (
    chain_code  TEXT   NOT NULL REFERENCES chains (code),
    address     TEXT   NOT NULL,
    next_nonce  BIGINT NOT NULL,
    PRIMARY KEY (chain_code, address)
);
