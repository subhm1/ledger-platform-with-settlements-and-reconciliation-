package com.ledger.platform.service;

import com.ledger.platform.api.ApiPage;
import com.ledger.platform.api.ReadApiException;
import com.ledger.platform.api.SettlementReadModels;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public final class SettlementReadService {
    private static final Set<String> VALIDATION_STATUSES = Set.of("VALID", "INVALID");
    private static final Set<String> OUTCOMES = Set.of(
            "MATCHED", "EXTERNAL_ONLY", "INTERNAL_ONLY", "DUPLICATE_EXTERNAL",
            "AMOUNT_MISMATCH", "AMBIGUOUS", "INVALID");

    private final JdbcTemplate jdbcTemplate;

    public SettlementReadService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ApiPage<SettlementReadModels.Batch> batches(
            String sourceCode, String reportId, int page, int size) {
        ReadPageRequest paging = ReadPageRequest.validate(page, size);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (sourceCode != null && !sourceCode.isBlank()) {
            where.append(" AND b.source_code = ?");
            parameters.add(sourceCode.trim());
        }
        if (reportId != null && !reportId.isBlank()) {
            where.append(" AND b.report_id = ?");
            parameters.add(reportId.trim());
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.settlement_batches b" + where,
                Long.class, parameters.toArray());
        List<Object> queryParameters = new ArrayList<>(parameters);
        queryParameters.add(paging.size());
        queryParameters.add(paging.offset());
        List<SettlementReadModels.Batch> items = jdbcTemplate.query("""
                SELECT b.id, b.source_code, b.report_id, b.file_sha256, b.status,
                       b.reporting_period_start, b.reporting_period_end, b.created_at,
                       COUNT(sr.id) AS record_count,
                       COUNT(sr.id) FILTER (WHERE sr.validation_status = 'VALID') AS valid_count,
                       COUNT(sr.id) FILTER (WHERE sr.validation_status = 'INVALID') AS invalid_count
                FROM public.settlement_batches b
                LEFT JOIN public.settlement_records sr ON sr.batch_id = b.id
                """ + where + "\n" + """
                GROUP BY b.id
                ORDER BY b.created_at DESC, b.id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new SettlementReadModels.Batch(
                rs.getObject("id", UUID.class),
                rs.getString("source_code"),
                rs.getString("report_id"),
                rs.getString("file_sha256"),
                rs.getString("status"),
                rs.getObject("reporting_period_start", java.time.OffsetDateTime.class),
                rs.getObject("reporting_period_end", java.time.OffsetDateTime.class),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getLong("record_count"),
                rs.getLong("valid_count"),
                rs.getLong("invalid_count")), queryParameters.toArray());
        return ApiPage.of(items, paging.page(), paging.size(), total);
    }

    public ApiPage<SettlementReadModels.SettlementRecord> records(
            UUID batchId, String validationStatus, int page, int size) {
        ReadPageRequest paging = ReadPageRequest.validate(page, size);
        requireBatch(batchId);
        String filter = " WHERE batch_id = ?";
        List<Object> parameters = new ArrayList<>();
        parameters.add(batchId);
        if (validationStatus != null && !validationStatus.isBlank()) {
            String status = validationStatus.trim().toUpperCase(java.util.Locale.ROOT);
            if (!VALIDATION_STATUSES.contains(status)) {
                throw new ReadApiException(HttpStatus.BAD_REQUEST,
                        "INVALID_VALIDATION_STATUS", "validationStatus must be VALID or INVALID.");
            }
            filter += " AND validation_status = ?";
            parameters.add(status);
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.settlement_records" + filter,
                Long.class, parameters.toArray());
        List<Object> queryParameters = new ArrayList<>(parameters);
        queryParameters.add(paging.size());
        queryParameters.add(paging.offset());
        List<SettlementReadModels.SettlementRecord> items = jdbcTemplate.query("""
                SELECT id, row_number, external_transaction_id, transaction_reference,
                       sr.currency_code, c.minor_unit_exponent,
                       gross_amount_minor::text AS gross_amount_minor,
                       fee_amount_minor::text AS fee_amount_minor,
                       adjustment_amount_minor::text AS adjustment_amount_minor,
                       net_amount_minor::text AS net_amount_minor,
                       validation_status,
                       ARRAY(SELECT jsonb_array_elements_text(validation_errors)) AS errors
                FROM public.settlement_records sr
                LEFT JOIN public.currencies c ON c.code = sr.currency_code
                """ + filter + "\n" + """
                ORDER BY row_number, id
                LIMIT ? OFFSET ?
                """, (rs, row) -> new SettlementReadModels.SettlementRecord(
                rs.getObject("id", UUID.class),
                rs.getInt("row_number"),
                rs.getString("external_transaction_id"),
                rs.getString("transaction_reference"),
                rs.getString("currency_code"),
                (Integer) rs.getObject("minor_unit_exponent"),
                rs.getString("gross_amount_minor"),
                rs.getString("fee_amount_minor"),
                rs.getString("adjustment_amount_minor"),
                rs.getString("net_amount_minor"),
                rs.getString("validation_status"),
                strings(rs.getArray("errors"))), queryParameters.toArray());
        return ApiPage.of(items, paging.page(), paging.size(), total);
    }

    public ApiPage<SettlementReadModels.ReconciliationRun> runs(
            UUID batchId, int page, int size) {
        ReadPageRequest paging = ReadPageRequest.validate(page, size);
        requireBatch(batchId);
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.reconciliation_runs WHERE batch_id = ?",
                Long.class, batchId);
        List<SettlementReadModels.ReconciliationRun> items = jdbcTemplate.query("""
                SELECT id, batch_id, run_number, rule_version, status, started_at, completed_at
                FROM public.reconciliation_runs
                WHERE batch_id = ?
                ORDER BY run_number DESC, id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new SettlementReadModels.ReconciliationRun(
                rs.getObject("id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getInt("run_number"),
                rs.getString("rule_version"),
                rs.getString("status"),
                rs.getObject("started_at", java.time.OffsetDateTime.class),
                rs.getObject("completed_at", java.time.OffsetDateTime.class)),
                batchId, paging.size(), paging.offset());
        return ApiPage.of(items, paging.page(), paging.size(), total);
    }

    public ApiPage<SettlementReadModels.ReconciliationResult> results(
            UUID runId, String outcome, int page, int size) {
        ReadPageRequest paging = ReadPageRequest.validate(page, size);
        requireRun(runId);
        String filter = " WHERE run_id = ?";
        List<Object> parameters = new ArrayList<>();
        parameters.add(runId);
        if (outcome != null && !outcome.isBlank()) {
            String value = outcome.trim().toUpperCase(java.util.Locale.ROOT);
            if (!OUTCOMES.contains(value)) {
                throw new ReadApiException(HttpStatus.BAD_REQUEST,
                        "INVALID_RECONCILIATION_OUTCOME", "outcome is not a supported classification.");
            }
            filter += " AND outcome = ?";
            parameters.add(value);
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.reconciliation_results" + filter,
                Long.class, parameters.toArray());
        List<Object> queryParameters = new ArrayList<>(parameters);
        queryParameters.add(paging.size());
        queryParameters.add(paging.offset());
        List<SettlementReadModels.ReconciliationResult> items = jdbcTemplate.query("""
                SELECT id, batch_id, run_id, external_record_id, internal_transaction_id,
                       outcome, details ->> 'transaction_reference' AS transaction_reference,
                       details ->> 'currency_code' AS currency_code,
                       c.minor_unit_exponent,
                       details ->> 'reported_gross_minor' AS reported_gross_minor,
                       details ->> 'internal_debit_total_minor' AS internal_debit_total_minor,
                       details ->> 'rule_version' AS rule_version,
                       ARRAY(SELECT jsonb_array_elements_text(
                           COALESCE(details -> 'validation_errors', '[]'::jsonb))) AS errors,
                       reconciliation_results.created_at
                FROM public.reconciliation_results
                LEFT JOIN public.currencies c
                  ON c.code = details ->> 'currency_code'
                """ + filter + "\n" + """
                ORDER BY reconciliation_results.created_at, reconciliation_results.id
                LIMIT ? OFFSET ?
                """, (rs, row) -> new SettlementReadModels.ReconciliationResult(
                rs.getObject("id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("external_record_id", UUID.class),
                rs.getObject("internal_transaction_id", UUID.class),
                rs.getString("outcome"),
                rs.getString("transaction_reference"),
                rs.getString("currency_code"),
                (Integer) rs.getObject("minor_unit_exponent"),
                rs.getString("reported_gross_minor"),
                rs.getString("internal_debit_total_minor"),
                rs.getString("rule_version"),
                strings(rs.getArray("errors")),
                rs.getObject("created_at", java.time.OffsetDateTime.class)),
                queryParameters.toArray());
        return ApiPage.of(items, paging.page(), paging.size(), total);
    }

    private void requireBatch(UUID batchId) {
        if (!exists("SELECT EXISTS(SELECT 1 FROM public.settlement_batches WHERE id = ?)", batchId)) {
            throw new ReadApiException(HttpStatus.NOT_FOUND,
                    "SETTLEMENT_BATCH_NOT_FOUND", "The settlement batch was not found.");
        }
    }

    private void requireRun(UUID runId) {
        if (!exists("SELECT EXISTS(SELECT 1 FROM public.reconciliation_runs WHERE id = ?)", runId)) {
            throw new ReadApiException(HttpStatus.NOT_FOUND,
                    "RECONCILIATION_RUN_NOT_FOUND", "The reconciliation run was not found.");
        }
    }

    private boolean exists(String sql, UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, id));
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        if (!(value instanceof Object[] elements)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>(elements.length);
        for (Object element : elements) {
            strings.add(String.valueOf(element));
        }
        return List.copyOf(strings);
    }
}
