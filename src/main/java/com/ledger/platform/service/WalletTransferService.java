package com.ledger.platform.service;

import com.ledger.platform.api.WalletModels;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public final class WalletTransferService {
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            "demo:shubham", "Shubham",
            "demo:ashwin", "Ashwin",
            "demo:arnav", "Arnav",
            "demo:shreya", "Shreya",
            "demo:swati", "Swati");

    private final JdbcTemplate jdbcTemplate;

    public WalletTransferService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<WalletModels.Wallet> listDemoWallets() {
        return jdbcTemplate.query("SELECT * FROM public.list_demo_wallets()",
                (rs, row) -> wallet(rs));
    }

    public WalletModels.TransferReceipt transfer(
            WalletModels.TransferRequest request,
            String idempotencyKey,
            String operator) {
        validate(request, idempotencyKey, operator);
        List<WalletModels.Wallet> wallets = listDemoWallets();
        Map<UUID, WalletModels.Wallet> byId = wallets.stream()
                .collect(Collectors.toMap(WalletModels.Wallet::walletId, Function.identity()));
        WalletModels.Wallet source = byId.get(request.sourceWalletId());
        WalletModels.Wallet destination = byId.get(request.destinationWalletId());
        if (source == null || destination == null) {
            throw badRequest("INVALID_DEMO_WALLET", "Choose wallets from the seeded demo wallet list.");
        }
        if (!"ACTIVE".equals(source.status()) || !"ACTIVE".equals(destination.status())) {
            throw rejected("WALLET_UNAVAILABLE", "Both demo wallets must be active to transfer.");
        }
        if (!source.currencyCode().equals(destination.currencyCode())) {
            throw rejected("CURRENCY_MISMATCH", "Demo wallet currencies must match.");
        }

        String reference = request.reference() == null ? "" : request.reference().trim();
        String description = reference.isEmpty()
                ? "Simulated internal wallet transfer"
                : "Simulated internal wallet transfer: " + reference;
        UUID transactionId;
        try {
            transactionId = jdbcTemplate.queryForObject("""
                    SELECT public.transfer_between_demo_wallets(
                        ?, ?, ?, ?, ?, ?, ?
                    )
                    """, UUID.class,
                    "demo-wallet-transfer:" + operator.trim(),
                    idempotencyKey.trim(),
                    request.transactionReference().trim(),
                    request.sourceWalletId(),
                    request.destinationWalletId(),
                    request.amountMinor(),
                    description);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
        if (transactionId == null) {
            throw new PostingException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "TRANSFER_FAILED", "The transfer could not be completed.");
        }

        PostedTransaction posted = jdbcTemplate.queryForObject("""
                SELECT id, transaction_reference, posted_at
                FROM public.journal_transactions WHERE id = ?
                """, (rs, row) -> new PostedTransaction(
                rs.getObject("id", UUID.class),
                rs.getString("transaction_reference"),
                timestamp(rs, "posted_at")), transactionId);

        return new WalletModels.TransferReceipt(
                posted.id(), posted.reference(),
                source.walletId(), source.displayName(),
                destination.walletId(), destination.displayName(),
                Long.toString(request.amountMinor()), source.currencyCode(),
                source.minorUnitExponent(), "POSTED", posted.postedAt());
    }

    private WalletModels.Wallet wallet(ResultSet rs) throws SQLException {
        String ownerReference = rs.getString("owner_reference");
        String displayName = DISPLAY_NAMES.get(ownerReference);
        if (displayName == null) {
            throw new SQLException("Demo wallet roster returned an unexpected owner reference");
        }
        String walletStatus = rs.getString("wallet_status");
        String accountStatus = rs.getString("account_status");
        String status = "ACTIVE".equals(walletStatus) && "ACTIVE".equals(accountStatus)
                ? "ACTIVE" : "UNAVAILABLE";
        return new WalletModels.Wallet(
                rs.getObject("wallet_id", UUID.class),
                displayName,
                rs.getString("currency_code"),
                rs.getInt("minor_unit_exponent"),
                status,
                rs.getString("balance_minor"));
    }

    private void validate(WalletModels.TransferRequest request, String key, String operator) {
        if (request == null) {
            throw badRequest("INVALID_REQUEST", "Transfer details are required.");
        }
        if (key == null || key.isBlank() || key.length() > 255) {
            throw badRequest("INVALID_IDEMPOTENCY_KEY", "A valid Idempotency-Key header is required.");
        }
        if (operator == null || operator.isBlank() || operator.length() > 120) {
            throw new PostingException(HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED", "Sign in before submitting a simulated transfer.");
        }
        if (request.sourceWalletId() == null || request.destinationWalletId() == null) {
            throw badRequest("INVALID_DEMO_WALLET", "Choose a sender and recipient wallet.");
        }
        if (request.sourceWalletId().equals(request.destinationWalletId())) {
            throw badRequest("SELF_TRANSFER", "Sender and recipient must be different wallets.");
        }
        if (request.amountMinor() == null || request.amountMinor() <= 0) {
            throw badRequest("INVALID_AMOUNT", "Transfer amount must be a positive integer number of paise.");
        }
        String transactionReference = request.transactionReference();
        if (transactionReference == null || transactionReference.length() > 150
                || !transactionReference.matches("[A-Za-z0-9][A-Za-z0-9:_-]{0,149}")) {
            throw badRequest("INVALID_REFERENCE", "A valid transfer reference is required.");
        }
        if (request.reference() != null && request.reference().trim().length() > 120) {
            throw badRequest("INVALID_REFERENCE", "Optional note must be at most 120 characters.");
        }
    }

    private PostingException translate(DataAccessException exception) {
        SQLException sql = findSqlException(exception);
        if (sql != null) {
            String state = sql.getSQLState();
            String message = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase(Locale.ROOT);
            if ("23505".equals(state)) {
                return new PostingException(HttpStatus.CONFLICT,
                        "TRANSFER_REFERENCE_CONFLICT", "That transfer reference is already in use.");
            }
            if ("P0001".equals(state)) {
                if (message.contains("idempotency key reused")) {
                    return new PostingException(HttpStatus.CONFLICT,
                            "IDEMPOTENCY_CONFLICT", "This retry key was already used for different transfer details.");
                }
                if (message.contains("insufficient wallet funds")) {
                    return new PostingException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "INSUFFICIENT_FUNDS", "The sender does not have enough simulated wallet balance.");
                }
                return new PostingException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "TRANSFER_REJECTED", "The transfer was rejected by the demo wallet rules.");
            }
        }
        return new PostingException(HttpStatus.INTERNAL_SERVER_ERROR,
                "TRANSFER_FAILED", "The transfer could not be completed.");
    }

    private SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sql) return sql;
            current = current.getCause();
        }
        return null;
    }

    private PostingException badRequest(String code, String message) {
        return new PostingException(HttpStatus.BAD_REQUEST, code, message);
    }

    private PostingException rejected(String code, String message) {
        return new PostingException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private static java.time.Instant timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record PostedTransaction(UUID id, String reference, java.time.Instant postedAt) { }
}
