-- Phase 1b: deposit addresses, deposits (user deposits and merchant pay-ins) and their status history.

CREATE TABLE address_derivation_counters (
    chain_code  TEXT PRIMARY KEY REFERENCES chains (code),
    next_index  BIGINT NOT NULL
);

-- One permanent address per owner and chain, derived from the chain's custody-held extended public key.
CREATE TABLE deposit_addresses (
    id                TEXT PRIMARY KEY,
    merchant_id       TEXT        NOT NULL REFERENCES merchants (id),
    owner_type        TEXT        NOT NULL CHECK (owner_type IN ('user', 'merchant')),
    owner_id          TEXT        NOT NULL,
    chain_code        TEXT        NOT NULL REFERENCES chains (code),
    address           TEXT        NOT NULL,
    derivation_index  BIGINT      NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'retired')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_type, owner_id, chain_code),
    UNIQUE (chain_code, address),
    UNIQUE (chain_code, derivation_index)
);

-- Deposit fee taken from the merchant's liquidity on each user deposit, in basis points.
ALTER TABLE merchants ADD COLUMN deposit_fee_bps INT NOT NULL DEFAULT 0 CHECK (deposit_fee_bps BETWEEN 0 AND 10000);

CREATE TABLE deposits (
    id                      TEXT PRIMARY KEY,
    merchant_id             TEXT          NOT NULL REFERENCES merchants (id),
    owner_type              TEXT          NOT NULL CHECK (owner_type IN ('user', 'merchant')),
    owner_id                TEXT          NOT NULL,
    address_id              TEXT          NOT NULL REFERENCES deposit_addresses (id),
    chain_code              TEXT          NOT NULL REFERENCES chains (code),
    asset_id                TEXT          REFERENCES assets (id),     -- NULL when the token is not supported
    token_contract          TEXT,                                      -- NULL for the chain's native coin
    amount                  NUMERIC(78,0) NOT NULL CHECK (amount >= 0),
    fee                     NUMERIC(78,0) NOT NULL DEFAULT 0,
    tx_hash                 TEXT          NOT NULL,
    output_index            INT           NOT NULL,                    -- log index (EVM/Tron) or vout (Bitcoin)
    block_number            BIGINT,
    block_hash              TEXT,
    confirmations           INT           NOT NULL DEFAULT 0,
    required_confirmations  INT           NOT NULL,
    status                  TEXT          NOT NULL CHECK (status IN
                                ('detected', 'confirming', 'completed', 'reversed', 'unsupported', 'needs_review')),
    status_reason           TEXT,
    simulated               BOOLEAN       NOT NULL DEFAULT FALSE,
    ledger_transaction_id   TEXT          REFERENCES ledger_transactions (id),
    detected_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (chain_code, tx_hash, output_index)
);
CREATE INDEX deposits_merchant_idx ON deposits (merchant_id, id DESC);
CREATE INDEX deposits_owner_idx ON deposits (owner_type, owner_id, id DESC);
CREATE INDEX deposits_open_idx ON deposits (chain_code, status) WHERE status IN ('detected', 'confirming');

CREATE TABLE deposit_status_history (
    id           BIGSERIAL PRIMARY KEY,
    deposit_id   TEXT        NOT NULL REFERENCES deposits (id),
    from_status  TEXT,
    to_status    TEXT        NOT NULL,
    reason       TEXT,
    at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX deposit_status_history_deposit_idx ON deposit_status_history (deposit_id, id);

CREATE TRIGGER deposit_status_history_append_only
    BEFORE UPDATE OR DELETE ON deposit_status_history
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();
