package com.cpg.gateway.deposit;

import com.cpg.gateway.apikey.MerchantPrincipal;
import com.cpg.gateway.audit.AuditLog;
import com.cpg.gateway.user.UserService;
import com.cpg.gateway.wallet.DepositAddressService;
import com.cpg.gateway.wallet.DepositAddressService.OwnerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class DepositController {

    private final DepositService deposits;
    private final DepositAddressService addresses;
    private final UserService users;

    public DepositController(DepositService deposits, DepositAddressService addresses, UserService users) {
        this.deposits = deposits;
        this.addresses = addresses;
        this.users = users;
    }

    // ---- Addresses -------------------------------------------------------------------------------------------

    /** Returns the user's permanent deposit address on the chain, creating it on first call. */
    @PostMapping("/v1/users/{userId}/deposit_addresses")
    public AddressView userAddress(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String userId,
                                   @Valid @RequestBody AddressRequest request) {
        users.require(merchant.merchantId(), userId);
        return AddressView.of(addresses.getOrCreate(merchant.merchantId(), OwnerType.user, userId, request.chain(),
                AuditLog.Actor.merchantKey(merchant.apiKeyId())));
    }

    @GetMapping("/v1/users/{userId}/deposit_addresses")
    public AddressList userAddresses(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String userId) {
        users.require(merchant.merchantId(), userId);
        return new AddressList(addresses.listForOwner(OwnerType.user, userId).stream().map(AddressView::of).toList());
    }

    /** The merchant's own pay-in address for funding liquidity. */
    @PostMapping("/v1/payin_addresses")
    public AddressView payinAddress(@AuthenticationPrincipal MerchantPrincipal merchant,
                                    @Valid @RequestBody AddressRequest request) {
        return AddressView.of(addresses.getOrCreate(merchant.merchantId(), OwnerType.merchant, merchant.merchantId(),
                request.chain(), AuditLog.Actor.merchantKey(merchant.apiKeyId())));
    }

    @GetMapping("/v1/payin_addresses")
    public AddressList payinAddresses(@AuthenticationPrincipal MerchantPrincipal merchant) {
        return new AddressList(addresses.listForOwner(OwnerType.merchant, merchant.merchantId()).stream()
                .map(AddressView::of).toList());
    }

    // ---- Deposits --------------------------------------------------------------------------------------------

    @GetMapping("/v1/deposits")
    public DepositList list(@AuthenticationPrincipal MerchantPrincipal merchant,
                            @RequestParam(name = "user_id", required = false) String userId,
                            @RequestParam(required = false) String type,
                            @RequestParam(required = false) String status,
                            @RequestParam(name = "starting_after", required = false) String startingAfter,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        String ownerType = userId != null ? "user" : "merchant_payin".equals(type) ? "merchant" : null;
        String ownerId = userId != null ? userId : "merchant".equals(ownerType) ? merchant.merchantId() : null;
        List<Deposit> page = deposits.list(merchant.merchantId(), ownerType, ownerId, status, startingAfter, limit + 1);
        boolean hasMore = page.size() > limit;
        return new DepositList((hasMore ? page.subList(0, limit) : page).stream()
                .map(d -> DepositView.of(d, deposits.decimals(d.assetId()))).toList(), hasMore);
    }

    @GetMapping("/v1/deposits/{id}")
    public DepositView get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        Deposit d = deposits.requireForMerchant(merchant.merchantId(), id);
        return DepositView.of(d, deposits.decimals(d.assetId()));
    }

    public record AddressRequest(@NotBlank String chain) {
    }

    public record AddressView(String id, String chain, String address, String ownerType, String ownerId,
                              Instant createdAt) {

        static AddressView of(DepositAddressService.DepositAddress a) {
            return new AddressView(a.id(), a.chain(), a.address(), a.ownerType().name(), a.ownerId(), a.createdAt());
        }
    }

    public record AddressList(List<AddressView> data) {
    }

    public record DepositList(List<DepositView> data, boolean hasMore) {
    }
}
