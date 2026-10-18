
package com.ledger.platform;

import org.flywaydb.core.Flyway;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.dao.DataAccessException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LedgerPlatformApplicationTests {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18")
                    .withDatabaseName("ledger")
                    .withUsername("postgres")
                    .withPassword("postgres_dev_password")
                    .withCommand("postgres", "-c", "timezone=Asia/Kolkata")
                    .withInitScript("test-db-roles.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> postgres.getJdbcUrl()
                        + "&options=-c%20TimeZone=Asia/Kolkata"
        );
        registry.add(
                "spring.datasource.username",
                () -> "ledger_runtime"
        );
        registry.add(
                "spring.datasource.password",
                () -> "local_runtime_password"
        );

        registry.add(
                "spring.flyway.url",
                () -> postgres.getJdbcUrl()
                        + "&options=-c%20TimeZone=Asia/Kolkata"
        );
        registry.add(
                "spring.flyway.user",
                () -> "ledger_migrator"
        );
        registry.add(
                "spring.flyway.password",
                () -> "local_migrator_password"
        );
    }

    private static final String ASSET_ACCOUNT =
            "11111111-1111-4111-8111-111111111111";
    private static final String LIABILITY_ACCOUNT =
            "22222222-2222-4222-8222-222222222222";

    @BeforeEach
    void seedLedgerAccounts() throws Exception {
        String url = postgres.getJdbcUrl()
                + "&options=-c%20TimeZone=Asia/Kolkata";

        try (Connection connection = DriverManager.getConnection(
                    url, "ledger_migrator", "local_migrator_password");
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE ledger_owner");

            statement.executeUpdate("""
                INSERT INTO public.currencies
                    (code, minor_unit_exponent, enabled)
                VALUES ('INR', 2, TRUE)
                ON CONFLICT (code) DO NOTHING
                """);

            statement.executeUpdate("""
                INSERT INTO public.accounts
                    (id, account_code, account_class, account_role, currency_code)
                VALUES
                    ('11111111-1111-4111-8111-111111111111',
                     'TEST_SETTLEMENT_CASH', 'ASSET', 'SETTLEMENT_CASH', 'INR'),
                    ('22222222-2222-4222-8222-222222222222',
                     'TEST_CUSTOMER_WALLET', 'LIABILITY', 'CUSTOMER_WALLET', 'INR')
                ON CONFLICT (id) DO NOTHING
                """);

            statement.executeUpdate("""
                UPDATE public.accounts
                   SET status = 'ACTIVE'
                 WHERE id IN (
                    '11111111-1111-4111-8111-111111111111',
                    '22222222-2222-4222-8222-222222222222'
                 )
                """);
        }
    }

    private void setAccountStatus(String status) throws Exception {
        String url = postgres.getJdbcUrl()
                + "&options=-c%20TimeZone=Asia/Kolkata";

        try (Connection connection = DriverManager.getConnection(
                    url, "ledger_migrator", "local_migrator_password");
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE ledger_owner");

            try (java.sql.PreparedStatement update = connection.prepareStatement(
                    "UPDATE public.accounts SET status = ? WHERE id = ?")) {
                update.setString(1, status);
                update.setObject(2, UUID.fromString(ASSET_ACCOUNT));
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
        }
    }

    private String reversalEntries() {
        return """
            [
              {"account_id":"11111111-1111-4111-8111-111111111111",
               "direction":"CREDIT","amount_minor":100},
              {"account_id":"22222222-2222-4222-8222-222222222222",
               "direction":"DEBIT","amount_minor":100}
            ]
            """;
    }
    private UUID post(
            String scope,
            String key,
            String reference,
            String entries,
            UUID reversalOf) {
        return jdbcTemplate.queryForObject(
                """
                SELECT public.post_journal_transaction(
                    ?, ?, ?, ?, ?, ?::jsonb, ?::uuid, ?::text, ?::text
                )
                """,
                UUID.class,
                scope,
                key,
                reference,
                "INR",
                "Ledger integration test",
                entries,
                reversalOf == null ? null : reversalOf.toString(),
                null,
                null
        );
    }

    private String balancedEntries() {
        return """
            [
              {"account_id":"11111111-1111-4111-8111-111111111111",
               "direction":"DEBIT","amount_minor":100},
              {"account_id":"22222222-2222-4222-8222-222222222222",
               "direction":"CREDIT","amount_minor":100}
            ]
            """;
    }

    @Test
    void balancedPostingPersistsEntriesAndRetryReturnsSameTransaction() {
        String scope = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        String reference = UUID.randomUUID().toString();

        UUID first = post(scope, key, reference, balancedEntries(), null);
        UUID retry = post(scope, key, reference, balancedEntries(), null);

        assertThat(retry).isEqualTo(first);

        Long entryCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.journal_entries WHERE transaction_id = ?",
                Long.class,
                first
        );
        assertThat(entryCount).isEqualTo(2L);
    }

    @Test
    void unbalancedPostingIsRejectedWithoutPersistingTransaction() {
        String reference = UUID.randomUUID().toString();
        String entries = """
            [
              {"account_id":"11111111-1111-4111-8111-111111111111",
               "direction":"DEBIT","amount_minor":100},
              {"account_id":"22222222-2222-4222-8222-222222222222",
               "direction":"CREDIT","amount_minor":90}
            ]
            """;

        assertThatThrownBy(() -> post(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                reference,
                entries,
                null
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Transaction is unbalanced: debits 100, credits 90");

        Long transactionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.journal_transactions WHERE transaction_reference = ?",
                Long.class,
                reference
        );
        assertThat(transactionCount).isZero();
    }

    @Test
    void runtimeCannotInsertJournalTransactionsDirectly() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO public.journal_transactions
                    (transaction_reference, currency_code, description)
                VALUES (?, 'INR', 'Direct-write permission test')
                """,
                UUID.randomUUID().toString()
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void fullReversalUsesOppositeEntriesAndReferencesOriginal() {
        String scope = UUID.randomUUID().toString();
        UUID originalId = post(
                scope,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                balancedEntries(),
                null
        );

        String reversalEntries = """
            [
              {"account_id":"11111111-1111-4111-8111-111111111111",
               "direction":"CREDIT","amount_minor":100},
              {"account_id":"22222222-2222-4222-8222-222222222222",
               "direction":"DEBIT","amount_minor":100}
            ]
            """;

        UUID reversalId = post(
                scope,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                reversalEntries,
                originalId
        );

        UUID recordedOriginal = jdbcTemplate.queryForObject(
                """
                SELECT reversal_of_transaction_id
                FROM public.journal_transactions
                WHERE id = ?
                """,
                UUID.class,
                reversalId
        );

        assertThat(recordedOriginal).isEqualTo(originalId);

        Long totalEntries = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM public.journal_entries
                WHERE transaction_id IN (?, ?)
                """,
                Long.class,
                originalId,
                reversalId
        );
        assertThat(totalEntries).isEqualTo(4L);
    }
    @Test
    void runtimeCannotFakeAuthorizationForSuspendedAccountReversal() throws Exception {
        String scope = UUID.randomUUID().toString();

        UUID originalId = post(
                scope, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), balancedEntries(), null);

        setAccountStatus("SUSPENDED");

        String reversalReference = UUID.randomUUID().toString();

        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                """
                SELECT public.post_journal_transaction(
                    ?, ?, ?, ?, ?, ?::jsonb, ?::uuid, ?::text, ?::text
                )
                """,
                UUID.class,
                scope,
                UUID.randomUUID().toString(),
                reversalReference,
                "INR",
                "Exceptional reversal test",
                reversalEntries(),
                originalId.toString(),
                "Fabricated Approver",
                "Claimed approval"
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Exceptional reversal requires approver role");

        Long reversalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.journal_transactions WHERE transaction_reference = ?",
                Long.class, reversalReference);
        assertThat(reversalCount).isZero();
    }

    @Test
    void authorizedDatabasePrincipalCanReverseSuspendedAccount() throws Exception {
        String scope = UUID.randomUUID().toString();

        UUID originalId = post(
                scope, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), balancedEntries(), null);

        setAccountStatus("SUSPENDED");

        String url = postgres.getJdbcUrl()
                + "&options=-c%20TimeZone=Asia/Kolkata";
        String reversalReference = UUID.randomUUID().toString();
        UUID reversalId;

        try (Connection connection = DriverManager.getConnection(
                    url, "ledger_approver_login", "local_approver_password");
             java.sql.PreparedStatement statement = connection.prepareStatement(
                """
                SELECT public.post_journal_transaction(
                    ?, ?, ?, ?, ?, ?::jsonb, ?::uuid, ?::text, ?::text
                )
                """)) {
            statement.setString(1, scope);
            statement.setString(2, UUID.randomUUID().toString());
            statement.setString(3, reversalReference);
            statement.setString(4, "INR");
            statement.setString(5, "Authorized exceptional reversal");
            statement.setString(6, reversalEntries());
            statement.setObject(7, originalId);
            statement.setString(8, "finance-control-test");
            statement.setString(9, "Approved exceptional reversal");

            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                reversalId = result.getObject(1, UUID.class);
            }
        }

        try (Connection connection = DriverManager.getConnection(
                    url, "ledger_migrator", "local_migrator_password");
             Statement ownerStatement = connection.createStatement()) {
            ownerStatement.execute("SET ROLE ledger_owner");

            try (java.sql.PreparedStatement auditStatement = connection.prepareStatement(
                """
                SELECT count(*)
                FROM public.reversal_audits
                WHERE original_transaction_id = ?
                  AND reversal_transaction_id = ?
                  AND authorized_by = ?
                  AND reason = ?
                """)) {
            auditStatement.setObject(1, originalId);
            auditStatement.setObject(2, reversalId);
            auditStatement.setString(3, "finance-control-test");
            auditStatement.setString(4, "Approved exceptional reversal");

            try (java.sql.ResultSet result = auditStatement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1L);
            }
            }
        }
    }

    @Test
    void contextLoads() {
        assertThat(jdbcTemplate).isNotNull();
    }

    @Test
    void connectsToPostgresAndRunsFlyway() {
        String timezone = jdbcTemplate.queryForObject(
                "SHOW TIME ZONE",
                String.class
        );

        String databaseName = jdbcTemplate.queryForObject(
                "SELECT current_database()",
                String.class
        );

        Boolean isSuperuser = jdbcTemplate.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user",
                Boolean.class
        );

        assertThat(timezone).isEqualTo("Asia/Kolkata");
        assertThat(databaseName).isEqualTo("ledger");
        assertThat(isSuperuser).isFalse();
        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo("2");
    }

    private String requestBody(
            String scope, String key, String reference, long debit, long credit) {
        return """
            {
              "scope": "%s",
              "idempotencyKey": "%s",
              "transactionReference": "%s",
              "currencyCode": "INR",
              "description": "API integration test",
              "entries": [
                {"accountId": "%s", "direction": "DEBIT", "amountMinor": %d},
                {"accountId": "%s", "direction": "CREDIT", "amountMinor": %d}
              ]
            }
            """.formatted(scope, key, reference,
                ASSET_ACCOUNT, debit, LIABILITY_ACCOUNT, credit);
    }

    @Test
    void validPostingReturnsPostedTransactionAndPersistsEntries() throws Exception {
        String reference = UUID.randomUUID().toString();
        String body = requestBody(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), reference, 100, 100);

        String response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/journal-transactions")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.transactionReference").value(reference))
                .andExpect(jsonPath("$.currencyCode").value("INR"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        UUID id = UUID.fromString(json.get("transactionId").asText());

        Long entries = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.journal_entries WHERE transaction_id = ?",
                Long.class, id);
        assertThat(entries).isEqualTo(2L);
    }

    @Test
    void sameIdempotencyKeyAndSameRequestReturnsSameTransaction() throws Exception {
        String scope = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        String body = requestBody(scope, key, UUID.randomUUID().toString(), 100, 100);

        String first = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/journal-transactions")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/journal-transactions")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("transactionId").asText())
                .isEqualTo(objectMapper.readTree(first).get("transactionId").asText());
    }

    @Test
    void unbalancedRequestReturns422WithoutPersistingTransaction() throws Exception {
        String reference = UUID.randomUUID().toString();
        String body = requestBody(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), reference, 100, 90);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/journal-transactions")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNBALANCED_TRANSACTION"));

        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.journal_transactions WHERE transaction_reference = ?",
                Long.class, reference);
        assertThat(count).isZero();
    }

    @Test
    void malformedJsonReturns400WithSanitizedError() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/journal-transactions")
                        .contentType("application/json").content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed."));
    }


    @Test
    void idempotencyKeyReuseWithDifferentRequestReturns409() throws Exception {
        String scope = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        String reference = UUID.randomUUID().toString();

        String originalBody = requestBody(scope, key, reference, 100, 100);
        String conflictingBody = requestBody(scope, key, reference, 200, 200);

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/journal-transactions")
                                .contentType("application/json")
                                .content(originalBody))
                .andExpect(status().isOk());

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/journal-transactions")
                                .contentType("application/json")
                                .content(conflictingBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        Long transactions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.journal_transactions WHERE transaction_reference = ?",
                Long.class, reference);
        assertThat(transactions).isEqualTo(1L);

        Long entries = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM public.journal_entries e
                JOIN public.journal_transactions t ON t.id = e.transaction_id
                WHERE t.transaction_reference = ?
                """,
                Long.class, reference);
        assertThat(entries).isEqualTo(2L);
     }
}
