package com.cpg.gateway.ledger;

import com.cpg.gateway.apikey.MerchantPrincipal;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BalanceController {

    private final BalanceQuery balances;

    public BalanceController(BalanceQuery balances) {
        this.balances = balances;
    }

    @GetMapping("/v1/balances")
    public BalanceList balances(@AuthenticationPrincipal MerchantPrincipal merchant) {
        return new BalanceList(balances.forOwner(AccountKind.Owner.MERCHANT, merchant.merchantId()));
    }

    public record BalanceList(List<BalanceQuery.Balance> data) {
    }
}
