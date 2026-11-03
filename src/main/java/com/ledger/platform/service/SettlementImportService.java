package com.ledger.platform.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.ledger.platform.api.SettlementImportResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class SettlementImportService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SettlementCsvParser csvParser;
    private final TransactionTemplate transactionTemplate;

    public SettlementImportService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SettlementCsvParser csvParser,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.csvParser = csvParser;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public SettlementImportResponse importReport(
            MultipartFile file,
            String sourceCode,
            String reportId,
            OffsetDateTime reportingPeriodStart,
            OffsetDateTime reportingPeriodEnd) {

        validateMetadata(file, sourceCode, reportId, reportingPeriodStart, reportingPeriodEnd);

        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new SettlementImportException(
                    HttpStatus.BAD_REQUEST, "FILE_READ_FAILED", "The uploaded file could not be read.");
        }

        SettlementCsvParser.ParsedReport parsed = csvParser.parse(bytes);
        String fileHash = sha256(bytes);
        String filename = safeFilename(file.getOriginalFilename());

        final String rowsJson;
        try {
            rowsJson = objectMapper.writeValueAsString(parsed.rows());
        } catch (JacksonException exception) {
            throw new SettlementImportException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "SERIALIZATION_FAILED",
                    "The settlement report could not be processed.");
        }

        try {
            ImportOutcome outcome = transactionTemplate.execute(transaction -> {
                jdbcTemplate.queryForObject("""
                        SELECT pg_catalog.pg_advisory_xact_lock(
                            pg_catalog.hashtext(?), pg_catalog.hashtext(?)) IS NULL
                        """, Boolean.class, sourceCode.trim(), reportId.trim());
                var existing = jdbcTemplate.query(
                        "SELECT id, file_sha256 FROM public.settlement_batches "
                                + "WHERE source_code = ? AND report_id = ?",
                        (resultSet, rowNumber) -> new ExistingBatch(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("file_sha256")),
                        sourceCode.trim(), reportId.trim());
                if (!existing.isEmpty() && existing.getFirst().fileHash().equals(fileHash)) {
                    return new ImportOutcome(existing.getFirst().batchId(), "ALREADY_IMPORTED");
                }
                UUID batchId = jdbcTemplate.queryForObject(
                        """
                        SELECT public.import_settlement_batch(
                            ?, ?, ?, ?, ?, ?, ?::jsonb
                        )
                        """,
                        (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                        sourceCode.trim(), reportId.trim(), fileHash, filename,
                        reportingPeriodStart, reportingPeriodEnd, rowsJson);
                return new ImportOutcome(batchId, "IMPORTED");
            });

            return new SettlementImportResponse(
                    outcome.batchId(), sourceCode.trim(), reportId.trim(), fileHash, outcome.status());
        } catch (DataAccessException exception) {
            if (containsMessage(exception, "Report identity already exists with different content")) {
                throw new SettlementImportException(
                        HttpStatus.CONFLICT, "REPORT_ID_CONFLICT",
                        "This source and report ID already exist with different file content.");
            }
            throw new SettlementImportException(
                    HttpStatus.BAD_REQUEST, "SETTLEMENT_IMPORT_REJECTED",
                    "The settlement report was rejected by database validation.");
        }
    }

    private static void validateMetadata(
            MultipartFile file,
            String sourceCode,
            String reportId,
            OffsetDateTime start,
            OffsetDateTime end) {
        if (file == null || file.isEmpty()) {
            throw new SettlementImportException(
                    HttpStatus.BAD_REQUEST, "EMPTY_FILE", "A non-empty CSV file is required.");
        }
        if (sourceCode == null || sourceCode.isBlank() || sourceCode.length() > 80) {
            throw new SettlementImportException(
                    HttpStatus.BAD_REQUEST, "INVALID_SOURCE_CODE",
                    "Source code must contain between 1 and 80 characters.");
        }
        if (reportId == null || reportId.isBlank() || reportId.length() > 150) {
            throw new SettlementImportException(
                    HttpStatus.BAD_REQUEST, "INVALID_REPORT_ID",
                    "Report ID must contain between 1 and 150 characters.");
        }
        if (start == null || end == null || !start.isBefore(end)) {
            throw new SettlementImportException(
                    HttpStatus.BAD_REQUEST, "INVALID_REPORTING_PERIOD",
                    "Reporting period start must be earlier than reporting period end.");
        }
        if (file.getSize() > SettlementCsvParser.MAX_FILE_BYTES) {
            throw new SettlementImportException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "The CSV file exceeds the 5 MiB limit.");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "settlement.csv";
        }
        String normalized = filename.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        return basename.isBlank() ? "settlement.csv" : basename;
    }

    private static boolean containsMessage(Throwable throwable, String expected) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private record ImportOutcome(UUID batchId, String status) { }

    private record ExistingBatch(UUID batchId, String fileHash) { }
}

