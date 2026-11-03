package com.ledger.platform;

import tools.jackson.databind.ObjectMapper;
import com.ledger.platform.service.SettlementCsvParser;
import com.ledger.platform.service.SettlementImportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCsvParserTests {
    private SettlementCsvParser parser;

    @BeforeEach
    void setUp() {
        parser = new SettlementCsvParser(new ObjectMapper());
    }

    @Test
    void parsesValidCsvAndChecksSettlementArithmetic() {
        String csv =
                "external_transaction_id,transaction_reference,currency_code,gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"
                + "ext-1,TXN-1001,INR,100000,2000,0,98000\n";

        var report = parser.parse(csv.getBytes(StandardCharsets.UTF_8));

        assertThat(report.rowCount()).isEqualTo(1);
        assertThat(report.rows().get(0).get("validation_status").asText()).isEqualTo("VALID");
        assertThat(report.rows().get(0).get("row_number").asInt()).isEqualTo(2);
    }

    @Test
    void retainsRowAsInvalidWhenArithmeticDoesNotBalance() {
        String csv =
                "external_transaction_id,transaction_reference,currency_code,gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"
                + "ext-1,TXN-1001,INR,100000,2000,0,97000\n";

        var report = parser.parse(csv.getBytes(StandardCharsets.UTF_8));

        assertThat(report.rows().get(0).get("validation_status").asText()).isEqualTo("INVALID");
        assertThat(report.rows().get(0).get("validation_errors").toString())
                .contains("NET_AMOUNT_FORMULA_MISMATCH");
    }


    @Test
void acceptsNegativeAdjustmentWhenNetArithmeticBalances() {
    String csv =
            "external_transaction_id,transaction_reference,currency_code,gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"
            + "ext-1,TXN-1001,INR,100,5,-2,93\n";

    var report = parser.parse(csv.getBytes(StandardCharsets.UTF_8));

    assertThat(report.rowCount()).isEqualTo(1);
    assertThat(report.rows().get(0).get("validation_status").asText())
            .isEqualTo("VALID");
}


    @Test
    void rejectsMissingOrExtraHeaders() {
        String csv = "transaction_reference,currency_code\nTXN-1,INR";

        assertThatThrownBy(() -> parser.parse(csv.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SettlementImportException.class)
                .hasMessageContaining("CSV headers");
    }

    @Test
    void rejectsRequiredHeadersInWrongOrder() {
        String csv = "transaction_reference,external_transaction_id,currency_code,"
                + "gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"
                + "TXN-1,ext-1,INR,100,0,0,100\n";

        assertThatThrownBy(() -> parser.parse(csv.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SettlementImportException.class)
                .hasMessageContaining("names and order exactly");
    }

    @Test
    void supportsQuotedCommasAndEscapedQuotes() {
        String csv =
                "external_transaction_id,transaction_reference,currency_code,gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"
                + "\"ext, \"\"one\"\"\",TXN-1001,INR,100,0,0,100\n";

        var report = parser.parse(csv.getBytes(StandardCharsets.UTF_8));

        assertThat(report.rows().get(0).get("raw_row").get("external_transaction_id").asText())
                .isEqualTo("ext, \"one\"");
    }

    @Test
    void rejectsUnclosedQuotes() {
        String csv =
                "external_transaction_id,transaction_reference,currency_code,gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"
                + "\"ext-1,TXN-1,INR,100,0,0,100\n";

        assertThatThrownBy(() -> parser.parse(csv.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SettlementImportException.class)
                .hasMessageContaining("malformed quoting");
    }
}
