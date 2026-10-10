package com.bankledger;

import java.math.BigDecimal;

/** One row of the transaction ledger (model class). */
public class LedgerEntry {
    private final long id;
    private final Integer fromAccount; // null for deposits
    private final Integer toAccount;
    private final BigDecimal amount;
    private final String type;
    private final String createdAt;

    public LedgerEntry(long id, Integer fromAccount, Integer toAccount,
                       BigDecimal amount, String type, String createdAt) {
        this.id = id;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.type = type;
        this.createdAt = createdAt;
    }

    public String toJson() {
        return "{\"id\":" + id
                + ",\"type\":\"" + type + "\""
                + ",\"fromAccount\":" + fromAccount
                + ",\"toAccount\":" + toAccount
                + ",\"amount\":" + amount.toPlainString()
                + ",\"createdAt\":\"" + createdAt + "\"}";
    }
}
