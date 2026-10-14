-- Phase 1c: block scanning state for the chain watchers.

-- One row per chain: the last block scanned and the watcher instance currently holding the scan lease.
CREATE TABLE chain_scan_cursors (
    chain_code   TEXT PRIMARY KEY REFERENCES chains (code),
    last_block   BIGINT,
    last_hash    TEXT,
    lease_owner  TEXT,
    lease_until  TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Recently scanned canonical blocks, kept to find the fork point when the chain reorganises.
CREATE TABLE chain_blocks (
    chain_code    TEXT        NOT NULL REFERENCES chains (code),
    block_number  BIGINT      NOT NULL,
    block_hash    TEXT        NOT NULL,
    parent_hash   TEXT        NOT NULL,
    transfers     INT         NOT NULL DEFAULT 0,
    scanned_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chain_code, block_number)
);
