-- Phase 1a: end users, user-owned ledger accounts, API idempotency, audit log, merchant webhooks.

-- End users belong to exactly one merchant and are identified to the merchant by its own reference.
CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    merchant_id   TEXT        NOT NULL REFERENCES merchants (id),
    external_ref  TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'blocked', 'closed')),
    kyc_status    TEXT        NOT NULL DEFAULT 'not_required'
                  CHECK (kyc_status IN ('not_required', 'required', 'pending', 'verified', 'rejected')),
    metadata      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, external_ref)
);

ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_accounts_owner_type_check;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_owner_type_check
    CHECK (owner_type IN ('user', 'merchant', 'platform'));

-- Merchant API idempotency. A key is claimed (response_status NULL) before the request runs, so concurrent
-- retries of the same key see an in-flight row instead of executing twice.
CREATE TABLE idempotency_keys (
    merchant_id      TEXT        NOT NULL REFERENCES merchants (id),
    key              TEXT        NOT NULL,
    request_method   TEXT        NOT NULL,
    request_path     TEXT        NOT NULL,
    request_hash     BYTEA       NOT NULL,
    response_status  INT,
    response_body    BYTEA,
    response_type    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    PRIMARY KEY (merchant_id, key)
);
CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);

-- Append-only record of every state-changing action by merchants, operators and the system.
CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    actor_type   TEXT        NOT NULL CHECK (actor_type IN ('merchant', 'operator', 'system')),
    actor_id     TEXT        NOT NULL,
    action       TEXT        NOT NULL,
    target_type  TEXT        NOT NULL,
    target_id    TEXT        NOT NULL,
    merchant_id  TEXT,
    details      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ip           TEXT,
    request_id   TEXT,
    at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_target_idx ON audit_log (target_type, target_id);
CREATE INDEX audit_log_merchant_idx ON audit_log (merchant_id, at);

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();

-- Events now carry the merchant they concern, so the webhook dispatcher can route them.
ALTER TABLE outbox_events ADD COLUMN merchant_id TEXT;

-- Merchant webhooks ---------------------------------------------------------------------------------

CREATE TABLE webhook_endpoints (
    id                TEXT PRIMARY KEY,
    merchant_id       TEXT        NOT NULL REFERENCES merchants (id),
    url               TEXT        NOT NULL,
    -- Signing secret encrypted with AES-256-GCM under a server key; needed in plaintext to sign payloads.
    secret_encrypted  BYTEA       NOT NULL,
    event_types       TEXT[]      NOT NULL,          -- e.g. {'*'} or {'user.created','deposit.completed'}
    status            TEXT        NOT NULL DEFAULT 'enabled' CHECK (status IN ('enabled', 'disabled')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX webhook_endpoints_merchant_idx ON webhook_endpoints (merchant_id) WHERE status = 'enabled';

CREATE TABLE webhook_deliveries (
    id                TEXT PRIMARY KEY,
    endpoint_id       TEXT        NOT NULL REFERENCES webhook_endpoints (id),
    event_id          TEXT        NOT NULL REFERENCES outbox_events (id),
    status            TEXT        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'succeeded', 'failed')),
    attempts          INT         NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_status_code  INT,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    UNIQUE (endpoint_id, event_id)
);
CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at) WHERE status = 'pending';
