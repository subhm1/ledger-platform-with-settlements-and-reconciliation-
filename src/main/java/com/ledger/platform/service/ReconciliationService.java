package com.ledger.platform.service;

import com.ledger.platform.api.ReadApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public final class ReconciliationService {
    private static final String RULE_VERSION = "settlement-v1";

    private final JdbcTemplate jdbcTemplate;

    public ReconciliationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID reconcile(UUID batchId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT public.reconcile_settlement_batch(?, ?)",
                    UUID.class, batchId, RULE_VERSION);
        } catch (DataAccessException exception) {
            if (containsMessage(exception, "Settlement batch was not found")) {
                throw new ReadApiException(HttpStatus.NOT_FOUND,
                        "SETTLEMENT_BATCH_NOT_FOUND", "The settlement batch was not found.");
            }
            throw exception;
        }
    }

    private static boolean containsMessage(Throwable throwable, String expected) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
        }
        return false;
    }
}
