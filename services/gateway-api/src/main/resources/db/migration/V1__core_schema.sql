-- Core schema: merchants, API keys, chain/asset registry, double-entry ledger, transactional outbox.
-- All money is stored in the asset's smallest unit as NUMERIC(78,0), which covers the uint256 range.

CREATE TABLE merchants (
    id          TEXT PRIMARY KEY,
    name        TEXT        NOT NULL,
    email       TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'closed')),
    -- Verification is optional by default; admins can require it per merchant or per country later.
    kyc_status  TEXT        NOT NULL DEFAULT 'not_required'
                CHECK (kyc_status IN ('not_required', 'required', 'pending', 'verified', 'rejected')),
    country     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX merchants_email_uq ON merchants (lower(email));

CREATE TABLE api_keys (
    id            TEXT PRIMARY KEY,
    merchant_id   TEXT        NOT NULL REFERENCES merchants (id),
    mode          TEXT        NOT NULL CHECK (mode IN ('test', 'live')),
    name          TEXT        NOT NULL,
    -- HMAC-SHA256(secret, server pepper). The plaintext secret is shown once and never stored.
    secret_hash   BYTEA       NOT NULL UNIQUE,
    last4         TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX api_keys_merchant_idx ON api_keys (merchant_id);

-- Chain and asset registry. New tokens on an existing chain are a row, not a code change.
CREATE TABLE chains (
    code                   TEXT PRIMARY KEY,
    name                   TEXT    NOT NULL,
    family                 TEXT    NOT NULL CHECK (family IN ('evm', 'tron', 'bitcoin')),
    evm_chain_id           BIGINT,
    native_asset_symbol    TEXT    NOT NULL,
    default_confirmations  INT     NOT NULL CHECK (default_confirmations > 0),
    enabled                BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE assets (
    id                TEXT PRIMARY KEY,           -- e.g. USDT_TRON
    chain_code        TEXT    NOT NULL REFERENCES chains (code),
    symbol            TEXT    NOT NULL,
    name              TEXT    NOT NULL,
    contract_address  TEXT,                       -- NULL for the chain's native coin
    decimals          INT     NOT NULL CHECK (decimals BETWEEN 0 AND 36),
    accept_payments   BOOLEAN NOT NULL DEFAULT TRUE,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (chain_code, symbol)
);

-- Double-entry ledger --------------------------------------------------------------------------

CREATE TABLE ledger_accounts (
    id              TEXT PRIMARY KEY,
    owner_type      TEXT NOT NULL CHECK (owner_type IN ('merchant', 'platform')),
    owner_id        TEXT NOT NULL,                -- merchant id, or 'platform'
    asset_id        TEXT NOT NULL REFERENCES assets (id),
    kind            TEXT NOT NULL,                -- available, pending, fees, hot_wallet, gas, clearing, ...
    normal_balance  TEXT NOT NULL CHECK (normal_balance IN ('debit', 'credit')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_type, owner_id, asset_id, kind)
);

CREATE TABLE ledger_transactions (
    id           TEXT PRIMARY KEY,
    type         TEXT        NOT NULL,
    -- Idempotency key for the posting, e.g. 'deposit:TRON:<txhash>:<log index>'. Posting twice is a no-op.
    reference    TEXT        NOT NULL UNIQUE,
    description  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  TEXT          NOT NULL REFERENCES ledger_transactions (id),
    account_id      TEXT          NOT NULL REFERENCES ledger_accounts (id),
    asset_id        TEXT          NOT NULL REFERENCES assets (id),
    direction       TEXT          NOT NULL CHECK (direction IN ('debit', 'credit')),
    amount          NUMERIC(78,0) NOT NULL CHECK (amount > 0),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ledger_entries_tx_idx ON ledger_entries (transaction_id);
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id, id);

-- Materialised balance per account, updated in the same transaction as the entries.
CREATE TABLE ledger_balances (
    account_id  TEXT          PRIMARY KEY REFERENCES ledger_accounts (id),
    balance     NUMERIC(78,0) NOT NULL DEFAULT 0,
    version     BIGINT        NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Ledger rows are append-only. Corrections are new reversing transactions.
CREATE FUNCTION ledger_forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();
CREATE TRIGGER ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();

-- Defence in depth: at commit time every transaction must balance per asset, whatever code wrote it.
CREATE FUNCTION ledger_check_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    unbalanced TEXT;
BEGIN
    SELECT asset_id INTO unbalanced
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id
    GROUP BY asset_id
    HAVING sum(CASE WHEN direction = 'debit' THEN amount ELSE -amount END) <> 0
    LIMIT 1;

    IF unbalanced IS NOT NULL THEN
        RAISE EXCEPTION 'ledger transaction % is unbalanced for asset %', NEW.transaction_id, unbalanced
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_balanced();

-- Transactional outbox: rows written with business changes, relayed to the event bus by a publisher.
CREATE TABLE outbox_events (
    id              TEXT PRIMARY KEY,
    aggregate_type  TEXT        NOT NULL,
    aggregate_id    TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX outbox_events_unpublished_idx ON outbox_events (created_at) WHERE published_at IS NULL;
