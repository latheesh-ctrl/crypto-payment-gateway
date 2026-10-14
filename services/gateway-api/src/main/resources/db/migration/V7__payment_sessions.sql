-- Phase 2b: payment sessions (a deposit bound to a merchant order, player reference, amount, asset and network),
-- per-attempt webhook delivery logs and webhook secret rotation.

-- Each session gets its own deposit address, so every transfer to it belongs to exactly one order.
ALTER TABLE deposit_addresses DROP CONSTRAINT deposit_addresses_owner_type_check;
ALTER TABLE deposit_addresses ADD CONSTRAINT deposit_addresses_owner_type_check
    CHECK (owner_type IN ('user', 'merchant', 'session'));
ALTER TABLE deposits DROP CONSTRAINT deposits_owner_type_check;
ALTER TABLE deposits ADD CONSTRAINT deposits_owner_type_check
    CHECK (owner_type IN ('user', 'merchant', 'session'));

CREATE TABLE payment_sessions (
    id                   TEXT PRIMARY KEY,
    merchant_id          TEXT          NOT NULL REFERENCES merchants (id),
    player_ref           TEXT          NOT NULL,
    merchant_order_id    TEXT          NOT NULL,
    chain_code           TEXT          NOT NULL REFERENCES chains (code),
    asset_id             TEXT          NOT NULL REFERENCES assets (id),
    amount_expected      NUMERIC(78,0) NOT NULL CHECK (amount_expected > 0),
    amount_received      NUMERIC(78,0) NOT NULL DEFAULT 0 CHECK (amount_received >= 0),
    address_id           TEXT          NOT NULL REFERENCES deposit_addresses (id),
    status               TEXT          NOT NULL CHECK (status IN
                             ('open', 'pending', 'confirming', 'completed', 'underpaid', 'overpaid', 'late_payment',
                              'expired', 'cancelled', 'needs_review')),
    expires_at           TIMESTAMPTZ   NOT NULL,
    return_url           TEXT,
    metadata             JSONB         NOT NULL DEFAULT '{}',
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ,
    UNIQUE (merchant_id, merchant_order_id)
);
CREATE INDEX payment_sessions_merchant_idx ON payment_sessions (merchant_id, id DESC);
CREATE INDEX payment_sessions_expiring_idx ON payment_sessions (expires_at) WHERE status = 'open';

CREATE TABLE payment_session_status_history (
    id           BIGSERIAL PRIMARY KEY,
    session_id   TEXT        NOT NULL REFERENCES payment_sessions (id),
    from_status  TEXT,
    to_status    TEXT        NOT NULL,
    reason       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payment_session_status_history_idx ON payment_session_status_history (session_id, id);
CREATE TRIGGER payment_session_status_history_append_only
    BEFORE UPDATE OR DELETE ON payment_session_status_history
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();

-- Every delivery attempt, kept for the merchant's delivery log.
CREATE TABLE webhook_delivery_attempts (
    id            BIGSERIAL PRIMARY KEY,
    delivery_id   TEXT        NOT NULL REFERENCES webhook_deliveries (id),
    attempt       INT         NOT NULL,
    status_code   INT,
    error         TEXT,
    duration_ms   INT         NOT NULL,
    attempted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX webhook_delivery_attempts_idx ON webhook_delivery_attempts (delivery_id, id);

-- During rotation both secrets sign each delivery, so merchants can switch without missing events.
ALTER TABLE webhook_endpoints
    ADD COLUMN previous_secret_encrypted  BYTEA,
    ADD COLUMN previous_secret_expires_at TIMESTAMPTZ;
