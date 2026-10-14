-- v1 chains and assets (mainnet). Contract addresses are the issuers' canonical mainnet deployments;
-- verify against the issuer before enabling live payments. Testnet contracts are configured per environment.

INSERT INTO chains (code, name, family, evm_chain_id, native_asset_symbol, default_confirmations) VALUES
    ('TRON',     'Tron',             'tron',    NULL,  'TRX', 19),
    ('ETHEREUM', 'Ethereum',         'evm',     1,     'ETH', 12),
    ('BITCOIN',  'Bitcoin',          'bitcoin', NULL,  'BTC', 2),
    ('BSC',      'BNB Smart Chain',  'evm',     56,    'BNB', 15),
    ('ARBITRUM', 'Arbitrum One',     'evm',     42161, 'ETH', 20);

INSERT INTO assets (id, chain_code, symbol, name, contract_address, decimals, accept_payments) VALUES
    ('TRX_TRON',      'TRON',     'TRX',  'Tron',       NULL,                                         6,  TRUE),
    ('USDT_TRON',     'TRON',     'USDT', 'Tether USD', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t',         6,  TRUE),
    ('ETH_ETHEREUM',  'ETHEREUM', 'ETH',  'Ether',      NULL,                                         18, TRUE),
    ('USDT_ETHEREUM', 'ETHEREUM', 'USDT', 'Tether USD', '0xdAC17F958D2ee523a2206206994597C13D831ec7', 6,  TRUE),
    ('USDC_ETHEREUM', 'ETHEREUM', 'USDC', 'USD Coin',   '0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48', 6,  TRUE),
    ('BTC_BITCOIN',   'BITCOIN',  'BTC',  'Bitcoin',    NULL,                                         8,  TRUE),
    -- BNB is held only to pay gas on BSC; it is not offered to customers in v1.
    ('BNB_BSC',       'BSC',      'BNB',  'BNB',        NULL,                                         18, FALSE),
    ('USDT_BSC',      'BSC',      'USDT', 'Tether USD', '0x55d398326f99059fF775485246999027B3197955', 18, TRUE),
    ('ETH_ARBITRUM',  'ARBITRUM', 'ETH',  'Ether',      NULL,                                         18, TRUE),
    ('USDC_ARBITRUM', 'ARBITRUM', 'USDC', 'USD Coin',   '0xaf88d065e77c8cC2239327C5EDb3A432268e5831', 6,  TRUE),
    ('USDT_ARBITRUM', 'ARBITRUM', 'USDT', 'Tether USD', '0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9', 6,  TRUE);
