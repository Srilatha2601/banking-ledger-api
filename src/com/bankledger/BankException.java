package com.bankledger;

/** Business-rule error that carries the HTTP status code to return. */
public class BankException extends Exception {
    private final int status;

    public BankException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
