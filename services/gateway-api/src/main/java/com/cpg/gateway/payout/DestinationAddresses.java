package com.cpg.gateway.payout;

import com.cpg.common.ApiException;
import com.cpg.gateway.wallet.AddressCodec;
import com.cpg.gateway.wallet.WalletProperties;
import org.bitcoinj.base.AddressParser;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.BitcoinNetwork;
import org.springframework.stereotype.Component;

/** Validates a payout destination for its chain and returns it in the canonical form stored and signed. */
@Component
public class DestinationAddresses {

    private static final String EVM_ZERO = "0x0000000000000000000000000000000000000000";

    private final WalletProperties wallets;

    public DestinationAddresses(WalletProperties wallets) {
        this.wallets = wallets;
    }

    public String normalize(String chain, String family, String address) {
        String trimmed = address == null ? "" : address.strip();
        try {
            return switch (family) {
                case "evm" -> {
                    String normalized = AddressCodec.normalizeEvm(trimmed);
                    if (normalized.equalsIgnoreCase(EVM_ZERO)) {
                        throw invalid(chain, "the zero address burns funds");
                    }
                    yield normalized;
                }
                case "tron" -> {
                    byte[] decoded = Base58.decodeChecked(trimmed);
                    if (decoded.length != 21 || decoded[0] != 0x41) {
                        throw invalid(chain, "not a Tron account address");
                    }
                    yield trimmed;
                }
                case "bitcoin" -> {
                    WalletProperties.Chain config = wallets.chains().get(chain);
                    boolean mainnet = config != null && config.mainnet();
                    yield AddressParser.getDefault(mainnet ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET)
                            .parseAddress(trimmed).toString();
                }
                default -> throw invalid(chain, "unsupported network");
            };
        } catch (IllegalArgumentException e) { // also bitcoinj's AddressFormatException
            throw invalid(chain, e.getMessage());
        }
    }

    private static ApiException invalid(String chain, String why) {
        return ApiException.badRequest("invalid_destination_address", "Not a valid " + chain + " address: " + why);
    }
}
