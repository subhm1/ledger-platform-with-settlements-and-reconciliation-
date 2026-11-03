package com.ledger.platform.service;

import com.ledger.platform.api.ApiPage;
import com.ledger.platform.api.ReadApiException;
import com.ledger.platform.api.TransactionReadModels;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public final class TransactionReadService {
    private final JdbcTemplate jdbcTemplate;

    public TransactionReadService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ApiPage<TransactionReadModels.Summary> list(
            String transactionReference, String currencyCode, int page, int size) {
        ReadPageRequest paging = ReadPageRequest.validate(page, size);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (transactionReference != null && !transactionReference.isBlank()) {
            where.append(" AND jt.transaction_reference = ?");
            parameters.add(transactionReference.trim());
        }
        if (currencyCode != null && !currencyCode.isBlank()) {
            String currency = currencyCode.trim();
            if (!currency.matches("[A-Z]{3}")) {
                throw new ReadApiException(HttpStatus.BAD_REQUEST,
                        "INVALID_CURRENCY_CODE", "currencyCode must be three uppercase letters.");
            }
            where.append(" AND jt.currency_code = ?");
            parameters.add(currency);
        }

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.journal_transactions jt" + where,
                Long.class, parameters.toArray());
        List<Object> queryParameters = new ArrayList<>(parameters);
        queryParameters.add(paging.size());
        queryParameters.add(paging.offset());
        List<TransactionReadModels.Summary> items = jdbcTemplate.query("""
                SELECT jt.id, jt.transaction_reference, jt.currency_code,
                       c.minor_unit_exponent,
                       COALESCE(SUM(je.amount_minor) FILTER (WHERE je.direction = 'DEBIT'), 0)::text
                           AS total_debit_minor,
                       jt.posted_at, jt.reversal_of_transaction_id
                FROM public.journal_transactions jt
                LEFT JOIN public.journal_entries je ON je.transaction_id = jt.id
                JOIN public.currencies c ON c.code = jt.currency_code
                """ + where + "\n" + """
                GROUP BY jt.id, c.minor_unit_exponent
                ORDER BY jt.posted_at DESC, jt.id DESC
                LIMIT ? OFFSET ?
                """, this::toSummary, queryParameters.toArray());
        return ApiPage.of(items, paging.page(), paging.size(), total);
    }

    public TransactionReadModels.Detail get(UUID transactionId) {
        List<TransactionReadModels.Detail> transactions = jdbcTemplate.query("""
                SELECT jt.id, jt.transaction_reference, jt.currency_code,
                       c.minor_unit_exponent, jt.description,
                       jt.posted_at, jt.reversal_of_transaction_id
                FROM public.journal_transactions jt
                JOIN public.currencies c ON c.code = jt.currency_code
                WHERE jt.id = ?
                """, (rs, row) -> new TransactionReadModels.Detail(
                rs.getObject("id", UUID.class),
                rs.getString("transaction_reference"),
                rs.getString("currency_code"),
                rs.getInt("minor_unit_exponent"),
                rs.getString("description"),
                "POSTED",
                instant(rs, "posted_at"),
                rs.getObject("reversal_of_transaction_id", UUID.class),
                List.of()), transactionId);
        if (transactions.isEmpty()) {
            throw new ReadApiException(HttpStatus.NOT_FOUND,
                    "TRANSACTION_NOT_FOUND", "The transaction was not found.");
        }
        TransactionReadModels.Detail header = transactions.getFirst();
        return new TransactionReadModels.Detail(
                header.transactionId(),
                header.transactionReference(),
                header.currencyCode(),
                header.minorUnitExponent(),
                header.description(),
                header.status(),
                header.postedAt(),
                header.reversalOfTransactionId(),
                entries(transactionId));
    }

    private List<TransactionReadModels.Entry> entries(UUID transactionId) {
        return jdbcTemplate.query("""
                SELECT je.id, je.account_id, a.account_code,
                       CASE demo.owner_reference
                           WHEN 'demo:shubham' THEN 'Shubham demo wallet'
                           WHEN 'demo:ashwin' THEN 'Ashwin demo wallet'
                           WHEN 'demo:arnav' THEN 'Arnav demo wallet'
                           WHEN 'demo:shreya' THEN 'Shreya demo wallet'
                           WHEN 'demo:swati' THEN 'Swati demo wallet'
                           ELSE a.account_code
                       END AS account_display_name,
                       a.account_role,
                       je.direction, je.amount_minor::text AS amount_minor,
                       je.currency_code, c.minor_unit_exponent
                FROM public.journal_entries je
                JOIN public.accounts a ON a.id = je.account_id
                JOIN public.currencies c ON c.code = je.currency_code
                LEFT JOIN public.list_demo_wallets() demo
                       ON a.account_code = 'WALLET:' || demo.wallet_id::text
                WHERE je.transaction_id = ?
                ORDER BY je.created_at, je.id
                """, (rs, row) -> new TransactionReadModels.Entry(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("account_code"),
                rs.getString("account_display_name"),
                rs.getString("account_role"),
                rs.getString("direction"),
                rs.getString("amount_minor"),
                rs.getString("currency_code"),
                rs.getInt("minor_unit_exponent")), transactionId);
    }

    private TransactionReadModels.Summary toSummary(ResultSet rs, int row) throws SQLException {
        return new TransactionReadModels.Summary(
                rs.getObject("id", UUID.class),
                rs.getString("transaction_reference"),
                rs.getString("currency_code"),
                rs.getInt("minor_unit_exponent"),
                rs.getString("total_debit_minor"),
                "POSTED",
                instant(rs, "posted_at"),
                rs.getObject("reversal_of_transaction_id", UUID.class));
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
