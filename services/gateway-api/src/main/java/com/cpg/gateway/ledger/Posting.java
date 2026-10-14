package com.cpg.gateway.ledger;

import com.cpg.common.ApiException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A balanced set of ledger entries to record atomically.
 *
 * @param reference globally unique idempotency key, e.g. {@code deposit:TRON:<txhash>:<index>}
 */
public record Posting(String type, String reference, String description, List<Entry> entries) {

    public record Entry(AccountRef account, AccountKind.Side direction, BigInteger amount) {

        public Entry {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(amount, "amount");
            if (amount.signum() <= 0) {
                throw ApiException.badRequest("ledger_invalid_amount", "Entry amounts must be positive");
            }
        }

        public static Entry debit(AccountRef account, BigInteger amount) {
            return new Entry(account, AccountKind.Side.DEBIT, amount);
        }

        public static Entry credit(AccountRef account, BigInteger amount) {
            return new Entry(account, AccountKind.Side.CREDIT, amount);
        }
    }

    public Posting {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reference, "reference");
        entries = List.copyOf(entries);
        if (entries.size() < 2) {
            throw ApiException.badRequest("ledger_unbalanced", "A posting needs at least two entries");
        }
        Map<String, BigInteger> net = new HashMap<>();
        for (Entry e : entries) {
            BigInteger signed = e.direction() == AccountKind.Side.DEBIT ? e.amount() : e.amount().negate();
            net.merge(e.account().assetId(), signed, BigInteger::add);
        }
        net.forEach((asset, sum) -> {
            if (sum.signum() != 0) {
                throw ApiException.badRequest("ledger_unbalanced",
                        "Debits and credits differ by " + sum + " for asset " + asset);
            }
        });
    }
}
