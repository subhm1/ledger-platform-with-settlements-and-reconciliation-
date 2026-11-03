package com.ledger.platform.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class SettlementCsvParser {
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    public static final List<String> REQUIRED_HEADERS = List.of(
            "external_transaction_id",
            "transaction_reference",
            "currency_code",
            "gross_amount_minor",
            "fee_amount_minor",
            "adjustment_amount_minor",
            "net_amount_minor"
    );

    private final ObjectMapper objectMapper;

    public SettlementCsvParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedReport parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalid("EMPTY_FILE", "The uploaded CSV file is empty.");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw invalid("FILE_TOO_LARGE", "The CSV file exceeds the 5 MiB limit.");
        }

        final String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid("INVALID_UTF8", "The CSV file must be valid UTF-8.");
        }

        final List<List<String>> records;
        try {
            records = parseCsv(content);
        } catch (IllegalArgumentException exception) {
            throw invalid("MALFORMED_CSV", "The CSV file contains malformed quoting.");
        }

        if (records.isEmpty()) {
            throw invalid("EMPTY_FILE", "The uploaded CSV file has no header row.");
        }

        List<String> headers = records.getFirst();
        if (!headers.equals(REQUIRED_HEADERS)) {
            throw invalid("INVALID_HEADERS",
                    "CSV headers must match the required names and order exactly.");
        }

        ArrayNode rows = objectMapper.createArrayNode();
        int persistedRowCount = 0;

        for (int recordIndex = 1; recordIndex < records.size(); recordIndex++) {
            List<String> values = records.get(recordIndex);

            if (values.size() == 1 && values.getFirst().isBlank()) {
                continue;
            }

            persistedRowCount++;
            ObjectNode raw = objectMapper.createObjectNode();
            for (int i = 0; i < headers.size(); i++) {
                raw.put(headers.get(i), i < values.size() ? values.get(i) : "");
            }
            for (int i = headers.size(); i < values.size(); i++) {
                raw.put("_extra_" + (i - headers.size() + 1), values.get(i));
            }

            List<String> errors = new ArrayList<>();
            if (values.size() != headers.size()) {
                errors.add("COLUMN_COUNT_MISMATCH");
            }

            Map<String, String> fields = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                fields.put(headers.get(i), i < values.size() ? values.get(i).trim() : "");
            }

            String externalId = nonBlank(fields.get("external_transaction_id"),
                    "MISSING_EXTERNAL_TRANSACTION_ID", errors);
            String transactionReference = nonBlank(fields.get("transaction_reference"),
                    "MISSING_TRANSACTION_REFERENCE", errors);
            String currency = nonBlank(fields.get("currency_code"),
                    "MISSING_CURRENCY_CODE", errors);

            if (currency != null && !currency.matches("[A-Z]{3}")) {
                errors.add("INVALID_CURRENCY_CODE");
            }

            Long gross = parseLong(fields.get("gross_amount_minor"),
                    "INVALID_GROSS_AMOUNT", errors);
            Long fee = parseLong(fields.get("fee_amount_minor"),
                    "INVALID_FEE_AMOUNT", errors);
            Long adjustment = parseLong(fields.get("adjustment_amount_minor"),
                    "INVALID_ADJUSTMENT_AMOUNT", errors);
            Long net = parseLong(fields.get("net_amount_minor"),
                    "INVALID_NET_AMOUNT", errors);

            if (gross != null && gross < 0) errors.add("NEGATIVE_GROSS_AMOUNT");
            if (fee != null && fee < 0) errors.add("NEGATIVE_FEE_AMOUNT");
            if (net != null && net < 0) errors.add("NEGATIVE_NET_AMOUNT");

            if (gross != null && fee != null && adjustment != null && net != null) {
                BigInteger expected = BigInteger.valueOf(gross)
                        .subtract(BigInteger.valueOf(fee))
                        .add(BigInteger.valueOf(adjustment));
                if (!expected.equals(BigInteger.valueOf(net))) {
                    errors.add("NET_AMOUNT_FORMULA_MISMATCH");
                }
            }

            ObjectNode row = objectMapper.createObjectNode();
            row.put("row_number", recordIndex + 1);
            row.put("raw_row_text", reconstructCsvRow(values));
            row.set("raw_row", raw);
            putNullable(row, "external_transaction_id", externalId);
            putNullable(row, "transaction_reference", transactionReference);
            putNullable(row, "currency_code", currency);
            putNullable(row, "gross_amount_minor", gross);
            putNullable(row, "fee_amount_minor", fee);
            putNullable(row, "adjustment_amount_minor", adjustment);
            putNullable(row, "net_amount_minor", net);
            row.put("validation_status", errors.isEmpty() ? "VALID" : "INVALID");

            ArrayNode errorArray = objectMapper.createArrayNode();
            errors.forEach(errorArray::add);
            row.set("validation_errors", errorArray);
            rows.add(row);
        }

        if (persistedRowCount == 0) {
            throw invalid("NO_DATA_ROWS", "The CSV must contain at least one data row.");
        }

        return new ParsedReport(rows, persistedRowCount);
    }

    private static List<List<String>> parseCsv(String input) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        closedQuote = true;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (closedQuote && c != ',' && c != '\r' && c != '\n') {
                throw new IllegalArgumentException("Unexpected character after quoted field");
            }

            if (c == '"' && field.isEmpty() && !closedQuote) {
                quoted = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
                closedQuote = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                records.add(record);
                record = new ArrayList<>();
                field.setLength(0);
                closedQuote = false;
            } else {
                if (c == '"') {
                    throw new IllegalArgumentException("Unexpected quote in unquoted field");
                }
                field.append(c);
            }
        }

        if (quoted) {
            throw new IllegalArgumentException("Unclosed quoted field");
        }

        if (closedQuote || !field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }

        // UTF-8 BOM is common in spreadsheet-exported CSV files.
        if (!records.isEmpty() && !records.getFirst().isEmpty()) {
            String first = records.getFirst().getFirst();
            if (first.startsWith("\uFEFF")) {
                records.getFirst().set(0, first.substring(1));
            }
        }
        return records;
    }

    private static String reconstructCsvRow(List<String> values) {
        return values.stream()
                .map(value -> {
                    if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                        return "\"" + value.replace("\"", "\"\"") + "\"";
                    }
                    return value;
                })
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static String nonBlank(String value, String error, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(error);
            return null;
        }
        return value;
    }

    private static Long parseLong(String value, String error, List<String> errors) {
        if (value == null || value.isBlank() || !value.matches("-?[0-9]+")) {
            errors.add(error);
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            errors.add(error);
            return null;
        }
    }

    private static void putNullable(ObjectNode node, String key, String value) {
        if (value == null) node.putNull(key);
        else node.put(key, value);
    }

    private static void putNullable(ObjectNode node, String key, Long value) {
        if (value == null) node.putNull(key);
        else node.put(key, value);
    }

    private static SettlementImportException invalid(String code, String message) {
        return new SettlementImportException(
                org.springframework.http.HttpStatus.BAD_REQUEST, code, message);
    }

    public record ParsedReport(ArrayNode rows, int rowCount) {
    }
}

