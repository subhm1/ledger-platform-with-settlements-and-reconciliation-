package com.ledger.platform.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;
import com.ledger.platform.api.JournalEntryRequest;
import com.ledger.platform.api.JournalTransactionResponse;
import com.ledger.platform.api.PostJournalTransactionRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class JournalPostingService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JournalPostingService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public JournalTransactionResponse post(
            PostJournalTransactionRequest request) {

        validate(request);

        String entriesJson = objectMapper.writeValueAsString(
                request.entries().stream()
                        .map(entry -> new DatabaseJournalEntry(
                                entry.accountId(),
                                entry.direction(),
                                entry.amountMinor()))
                        .toList());

        UUID transactionId;
        try {
            transactionId = jdbcTemplate.queryForObject(
                    """
                    SELECT public.post_journal_transaction(
                        ?, ?, ?, ?, ?, ?::jsonb,
                        NULL::uuid, NULL::text, NULL::text
                    )
                    """,
                    UUID.class,
                    request.scope().trim(),
                    request.idempotencyKey().trim(),
                    request.transactionReference().trim(),
                    request.currencyCode(),
                    request.description().trim(),
                    entriesJson
            );
        } catch (DataAccessException exception) {
            throw translateDatabaseError(exception);
        }

        if (transactionId == null) {
            throw new IllegalStateException(
                    "The posting function returned no transaction identifier.");
        }

        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT id, transaction_reference, currency_code, posted_at
                    FROM public.journal_transactions
                    WHERE id = ?
                    """,
                    (ResultSet rs, int row) -> {
                        Timestamp postedAt = rs.getTimestamp("posted_at");
                        return new JournalTransactionResponse(
                                rs.getObject("id", UUID.class),
                                rs.getString("transaction_reference"),
                                rs.getString("currency_code"),
                                "POSTED",
                                postedAt == null ? null : postedAt.toInstant()
                        );
                    },
                    transactionId
            );
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "Posting returned an identifier but its record could not be read.",
                    exception);
        }
    }

    private void validate(PostJournalTransactionRequest request) {
        if (request == null) {
            throw badRequest("INVALID_REQUEST", "Request body is required.");
        }

        requireText(request.scope(), "scope");
        requireText(request.idempotencyKey(), "idempotencyKey");
        requireText(request.transactionReference(), "transactionReference");
        requireText(request.description(), "description");

        if (request.currencyCode() == null
                || !request.currencyCode().matches("[A-Z]{3}")) {
            throw badRequest(
                    "INVALID_CURRENCY_CODE",
                    "currencyCode must be a three-letter uppercase code.");
        }

        List<JournalEntryRequest> entries = request.entries();
        if (entries == null || entries.size() < 2) {
            throw badRequest(
                    "INVALID_ENTRIES",
                    "At least two journal entries are required.");
        }

        BigInteger debits = BigInteger.ZERO;
        BigInteger credits = BigInteger.ZERO;

        for (int i = 0; i < entries.size(); i++) {
            JournalEntryRequest entry = entries.get(i);

            if (entry == null) {
                throw badRequest(
                        "INVALID_ENTRY",
                        "Journal entry at index " + i + " is required.");
            }
            if (entry.accountId() == null) {
                throw badRequest(
                        "INVALID_ACCOUNT_ID",
                        "Every entry must contain a valid accountId.");
            }
            if (!"DEBIT".equals(entry.direction())
                    && !"CREDIT".equals(entry.direction())) {
                throw badRequest(
                        "INVALID_DIRECTION",
                        "Entry direction must be DEBIT or CREDIT.");
            }
            if (entry.amountMinor() == null || entry.amountMinor() <= 0) {
                throw badRequest(
                        "INVALID_AMOUNT",
                        "Every amountMinor must be a positive integer.");
            }

            BigInteger amount = BigInteger.valueOf(entry.amountMinor());
            if ("DEBIT".equals(entry.direction())) {
                debits = debits.add(amount);
            } else {
                credits = credits.add(amount);
            }
        }

        if (!debits.equals(credits)) {
            throw new PostingException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UNBALANCED_TRANSACTION",
                    "Total debits must equal total credits.");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(
                    "MISSING_REQUIRED_FIELD",
                    field + " is required and must not be blank.");
        }
    }

    private PostingException badRequest(String code, String message) {
        return new PostingException(HttpStatus.BAD_REQUEST, code, message);
    }

    private PostingException translateDatabaseError(
            DataAccessException exception) {

        SQLException sql = findSqlException(exception);
        if (sql != null) {
            String state = sql.getSQLState();
            String message = sql.getMessage();

            if ("23505".equals(state)) {
                return new PostingException(
                        HttpStatus.CONFLICT,
                        "UNIQUENESS_CONFLICT",
                        "The request conflicts with an existing financial record.");
            }

            if ("P0001".equals(state)) {
                if (message != null
                        && message.toLowerCase(Locale.ROOT)
                        .contains("idempotency key reused")) {
                    return new PostingException(
                            HttpStatus.CONFLICT,
                            "IDEMPOTENCY_CONFLICT",
                            "The idempotency key was already used with a different request.");
                }

                return new PostingException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "POSTING_REJECTED",
                        "The database rejected the posting under accounting rules.");
            }
        }

        return new PostingException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "POSTING_FAILED",
                "The posting could not be completed.");
    }

    private SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sql) {
                return sql;
            }
            current = current.getCause();
        }
        return null;
    }

    private record DatabaseJournalEntry(
            @JsonProperty("account_id") UUID accountId,
            @JsonProperty("direction") String direction,
            @JsonProperty("amount_minor") Long amountMinor
    ) {}}
