package com.cpg.gateway.ledger;

/**
 * The kinds of ledger account and the side on which each one's balance grows.
 *
 * <p>Platform wallet accounts are assets of the platform (debit-normal). User and merchant balances and fee
 * revenue are what the platform owes or has earned (credit-normal). A customer deposit is therefore
 * {@code debit DEPOSIT_WALLETS / credit MERCHANT_PENDING}, and confirmation moves pending to available.
 */
public enum AccountKind {
    USER_PENDING("pending", Owner.USER, Side.CREDIT),
    USER_AVAILABLE("available", Owner.USER, Side.CREDIT),
    USER_RESERVED("reserved", Owner.USER, Side.CREDIT),
    MERCHANT_PENDING("pending", Owner.MERCHANT, Side.CREDIT),
    MERCHANT_AVAILABLE("available", Owner.MERCHANT, Side.CREDIT),
    /** Crypto held for the merchant that can fund its users' cashouts (pay-ins plus its users' deposits). */
    MERCHANT_LIQUIDITY("liquidity", Owner.MERCHANT, Side.CREDIT),
    /** Liquidity earmarked for cashouts that are in flight. */
    MERCHANT_LIQUIDITY_ALLOCATED("liquidity_allocated", Owner.MERCHANT, Side.CREDIT),
    /**
     * What the merchant owes its users in total. Debit-normal contra account that lets a user deposit credit both
     * the user's balance and the merchant's liquidity without counting the same coins twice.
     */
    MERCHANT_USER_OBLIGATIONS("user_obligations", Owner.MERCHANT, Side.DEBIT),
    PLATFORM_DEPOSIT_WALLETS("deposit_wallets", Owner.PLATFORM, Side.DEBIT),
    PLATFORM_HOT_WALLET("hot_wallet", Owner.PLATFORM, Side.DEBIT),
    PLATFORM_COLD_WALLET("cold_wallet", Owner.PLATFORM, Side.DEBIT),
    PLATFORM_GAS("gas", Owner.PLATFORM, Side.DEBIT),
    PLATFORM_FEES("fees", Owner.PLATFORM, Side.CREDIT),
    PLATFORM_NETWORK_FEE_EXPENSE("network_fee_expense", Owner.PLATFORM, Side.DEBIT);

    public enum Owner { USER, MERCHANT, PLATFORM }

    public enum Side { DEBIT, CREDIT }

    private final String code;
    private final Owner owner;
    private final Side normalBalance;

    AccountKind(String code, Owner owner, Side normalBalance) {
        this.code = code;
        this.owner = owner;
        this.normalBalance = normalBalance;
    }

    public String code() {
        return code;
    }

    public Owner owner() {
        return owner;
    }

    public Side normalBalance() {
        return normalBalance;
    }

    /** User and merchant balances are liabilities to real people and must never go below zero. */
    public boolean mayGoNegative() {
        return owner == Owner.PLATFORM && this != PLATFORM_FEES;
    }
}
