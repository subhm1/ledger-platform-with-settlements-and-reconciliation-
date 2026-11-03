package com.ledger.platform.service;

import org.springframework.http.HttpStatus;

public final class SettlementImportException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public SettlementImportException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
