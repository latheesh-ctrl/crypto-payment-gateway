package com.cpg.gateway.user;

import com.cpg.gateway.apikey.MerchantPrincipal;
import com.cpg.gateway.audit.AuditLog;
import com.cpg.gateway.ledger.AccountKind;
import com.cpg.gateway.ledger.BalanceQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/users")
public class UserController {

    private final UserService users;
    private final BalanceQuery balances;

    public UserController(UserService users, BalanceQuery balances) {
        this.users = users;
        this.balances = balances;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public User create(@AuthenticationPrincipal MerchantPrincipal merchant,
                       @Valid @RequestBody CreateUserRequest request) {
        return users.create(merchant.merchantId(), request.externalRef(), request.metadata(),
                AuditLog.Actor.merchantKey(merchant.apiKeyId()));
    }

    @GetMapping("/{id}")
    public User get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        return users.require(merchant.merchantId(), id);
    }

    @GetMapping
    public UserList list(@AuthenticationPrincipal MerchantPrincipal merchant,
                         @RequestParam(name = "external_ref", required = false) String externalRef,
                         @RequestParam(name = "starting_after", required = false) String startingAfter,
                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        if (externalRef != null) {
            return new UserList(users.findByExternalRef(merchant.merchantId(), externalRef).stream().toList(), false);
        }
        List<User> page = users.list(merchant.merchantId(), startingAfter, limit + 1);
        boolean hasMore = page.size() > limit;
        return new UserList(hasMore ? page.subList(0, limit) : page, hasMore);
    }

    @GetMapping("/{id}/balances")
    public BalanceList balances(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        users.require(merchant.merchantId(), id);
        return new BalanceList(balances.forOwner(AccountKind.Owner.USER, id));
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 128) @Pattern(regexp = "[\\x21-\\x7E]+", message = "must be printable ASCII without spaces")
            String externalRef,
            @Size(max = 50) Map<String, Object> metadata) {
    }

    public record UserList(List<User> data, boolean hasMore) {
    }

    public record BalanceList(List<BalanceQuery.Balance> data) {
    }
}
