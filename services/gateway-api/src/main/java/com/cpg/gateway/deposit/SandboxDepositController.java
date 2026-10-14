package com.cpg.gateway.deposit;

import com.cpg.common.ApiException;
import com.cpg.common.Money;
import com.cpg.gateway.apikey.MerchantPrincipal;
import com.cpg.gateway.config.GatewayProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sandbox only: lets a merchant simulate a confirmed deposit to one of its own addresses so it can test its
 * integration end to end without testnet coins. Returns 404 in live mode.
 */
@RestController
public class SandboxDepositController {

    private final DepositService deposits;
    private final GatewayProperties properties;
    private final JdbcClient jdbc;

    public SandboxDepositController(DepositService deposits, GatewayProperties properties, JdbcClient jdbc) {
        this.deposits = deposits;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @PostMapping("/v1/sandbox/simulate_deposit")
    public DepositView simulate(@AuthenticationPrincipal MerchantPrincipal merchant,
                                @Valid @RequestBody SimulateRequest request) {
        if (properties.mode() != GatewayProperties.Mode.test) {
            throw ApiException.notFound("Resource");
        }
        record AddressInfo(String chain, String merchantId) {
        }
        AddressInfo address = jdbc.sql("SELECT chain_code, merchant_id FROM deposit_addresses WHERE address = :a")
                .param("a", request.address())
                .query((rs, i) -> new AddressInfo(rs.getString(1), rs.getString(2)))
                .optional()
                .filter(a -> a.merchantId().equals(merchant.merchantId()))
                .orElseThrow(() -> ApiException.notFound("Deposit address"));
        record AssetInfo(String chain, String contract, int decimals) {
        }
        AssetInfo asset = jdbc.sql("SELECT chain_code, contract_address, decimals FROM assets WHERE id = :id")
                .param("id", request.asset())
                .query((rs, i) -> new AssetInfo(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .optional()
                .filter(a -> a.chain().equals(address.chain()))
                .orElseThrow(() -> ApiException.badRequest("asset_chain_mismatch",
                        "Asset " + request.asset() + " is not on the address's chain"));

        Money amount;
        try {
            amount = Money.parse(request.asset(), request.amount(), asset.decimals());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_amount", e.getMessage());
        }
        String txHash = "sim_" + UUID.randomUUID().toString().replace("-", "");
        Deposit d = deposits.simulate(new ObservedTransfer(address.chain(), txHash, 0, request.address(),
                asset.contract(), amount.minorUnits(), 1L, "sim")).orElseThrow();
        d = deposits.onConfirmations(address.chain(), txHash, 0, 1L, "sim", d.requiredConfirmations()).orElseThrow();
        return DepositView.of(d, asset.decimals());
    }

    public record SimulateRequest(@NotBlank String address, @NotBlank String asset,
                                  @NotBlank @Pattern(regexp = "\\d+(\\.\\d+)?", message = "must be a positive decimal") String amount) {
    }
}
