package com.ledger.platform.service;

import com.ledger.platform.api.ReadApiException;
import org.springframework.http.HttpStatus;

public record ReadPageRequest(int page, int size) {
    public static final int MAX_SIZE = 100;

    public static ReadPageRequest validate(int page, int size) {
        if (page < 0) {
            throw new ReadApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_PAGE", "page must be zero or greater.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ReadApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE",
                    "size must be between 1 and " + MAX_SIZE + ".");
        }
        return new ReadPageRequest(page, size);
    }

    public long offset() {
        return (long) page * size;
    }
}
