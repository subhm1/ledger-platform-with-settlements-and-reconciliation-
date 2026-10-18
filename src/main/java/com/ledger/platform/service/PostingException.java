package com.ledger.platform.service;

import org.springframework.http.HttpStatus;

public class PostingException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PostingException(HttpStatus status, String code, String message) {
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