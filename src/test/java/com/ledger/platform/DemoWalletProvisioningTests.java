package com.ledger.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DemoWalletProvisioningTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("ledger")
            .withUsername("postgres")
            .withPassword("postgres_dev_password")
            .withInitScript("test-db-roles.sql");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("ledger.security.username", () -> "operator");
        registry.add("ledger.security.password", () -> "test-only-long-password-123456789");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "ledger_runtime");
        registry.add("spring.datasource.password", () -> "local_runtime_password");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "ledger_migrator");
        registry.add("spring.flyway.password", () -> "local_migrator_password");
    }

    @Test
    void provisioningIsAtomicBalancedAndIdempotentAndRejectsUnsafeState() throws Exception {
        String provisioningSql = provisioningSql();

        assertThat(runProvisioner(provisioningSql, 10_000).getExitCode()).isNotZero();
        assertThat(count("SELECT count(*) FROM public.wallets")).isZero();

        asLedgerOwner(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO public.currencies (code, minor_unit_exponent, enabled) "
                        + "VALUES ('INR', 2, TRUE)");
                statement.execute("SELECT public.create_wallet('demo:ashwin', 'INR')");
                statement.execute("UPDATE public.wallets SET status = 'SUSPENDED' "
                        + "WHERE owner_reference = 'demo:ashwin' AND currency_code = 'INR'");
            }
        });

        assertThat(runProvisioner(provisioningSql, 10_000).getExitCode()).isNotZero();
        assertThat(count("SELECT count(*) FROM public.accounts WHERE account_code = 'DEMO:TREASURY:INR'")).isZero();
        assertThat(count("SELECT count(*) FROM public.wallets WHERE owner_reference LIKE 'demo:%'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.journal_transactions "
                + "WHERE transaction_reference LIKE 'demo-wallet-opening-v1:%'")).isZero();

        asLedgerOwner(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE public.wallets SET status = 'ACTIVE' "
                        + "WHERE owner_reference = 'demo:ashwin' AND currency_code = 'INR'");
            }
        });

        var successfulProvisioning = runProvisioner(provisioningSql, 10_000);
        assertThat(successfulProvisioning.getExitCode())
                .withFailMessage("Provisioning failed: %s %s", successfulProvisioning.getStdout(), successfulProvisioning.getStderr())
                .isZero();
        assertThat(count("SELECT count(*) FROM public.wallets WHERE owner_reference LIKE 'demo:%'")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM public.journal_transactions "
                + "WHERE transaction_reference LIKE 'demo-wallet-opening-v1:%'")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM public.journal_entries e "
                + "JOIN public.journal_transactions t ON t.id = e.transaction_id "
                + "WHERE t.transaction_reference LIKE 'demo-wallet-opening-v1:%'")).isEqualTo(10);
        assertThat(walletsAndBalances()).containsExactlyInAnyOrder(
                Map.entry("demo:shubham", 10_000L),
                Map.entry("demo:ashwin", 10_000L),
                Map.entry("demo:arnav", 10_000L),
                Map.entry("demo:shreya", 10_000L),
                Map.entry("demo:swati", 10_000L));
        assertThat(count("SELECT count(*) FROM ("
                + "SELECT t.id FROM public.journal_transactions t "
                + "JOIN public.journal_entries e ON e.transaction_id = t.id "
                + "WHERE t.transaction_reference LIKE 'demo-wallet-opening-v1:%' "
                + "GROUP BY t.id HAVING sum(e.amount_minor::numeric) FILTER (WHERE e.direction = 'DEBIT') "
                + "<> sum(e.amount_minor::numeric) FILTER (WHERE e.direction = 'CREDIT')) unbalanced")).isZero();

        assertThat(runProvisioner(provisioningSql, 10_000).getExitCode()).isZero();
        assertThat(count("SELECT count(*) FROM public.journal_transactions "
                + "WHERE transaction_reference LIKE 'demo-wallet-opening-v1:%'")).isEqualTo(5);

        assertThat(runProvisioner(provisioningSql, 12_000).getExitCode()).isNotZero();
        assertThat(walletsAndBalances()).containsExactlyInAnyOrder(
                Map.entry("demo:shubham", 10_000L),
                Map.entry("demo:ashwin", 10_000L),
                Map.entry("demo:arnav", 10_000L),
                Map.entry("demo:shreya", 10_000L),
                Map.entry("demo:swati", 10_000L));
        assertThat(count("SELECT count(*) FROM public.journal_transactions "
                + "WHERE transaction_reference LIKE 'demo-wallet-opening-v1:%'")).isEqualTo(5);

        verifyWalletTransferApi();
    }

    private void verifyWalletTransferApi() throws Exception {
        mockMvc.perform(get("/api/v1/wallets").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/wallets").with(user("reader").roles("READER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/wallets").with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.displayName == 'Shubham')]").exists())
                .andExpect(jsonPath("$[?(@.displayName == 'Swati')]").exists());

        var source = walletId("demo:shubham");
        var destination = walletId("demo:ashwin");
        String transactionReference = "API-XFER-" + java.util.UUID.randomUUID();
        String body = """
                {"sourceWalletId":"%s","destinationWalletId":"%s","amountMinor":2500,
                 "transactionReference":"%s","reference":"stage-2-api-test"}
                """.formatted(source, destination, transactionReference);
        String idempotencyKey = "api-transfer-" + java.util.UUID.randomUUID();

        String firstResponse = mockMvc.perform(post("/api/v1/wallets/transfers")
                        .with(user("operator").roles("OPERATOR"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceName").value("Shubham"))
                .andExpect(jsonPath("$.destinationName").value("Ashwin"))
                .andExpect(jsonPath("$.amountMinor").value("2500"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode first = objectMapper.readTree(firstResponse);

        mockMvc.perform(get("/api/v1/transactions/{transactionId}", first.get("transactionId").asText())
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.accountDisplayName == 'Shubham demo wallet')]").exists())
                .andExpect(jsonPath("$.entries[?(@.accountDisplayName == 'Ashwin demo wallet')]").exists())
                .andExpect(jsonPath("$.entries[?(@.accountRole == 'CUSTOMER_WALLET')]").exists());

        String retryResponse = mockMvc.perform(post("/api/v1/wallets/transfers")
                        .with(user("operator").roles("OPERATOR"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(retryResponse).get("transactionId").asText())
                .isEqualTo(first.get("transactionId").asText());

        String changedBody = body.replace("2500", "2600");
        mockMvc.perform(post("/api/v1/wallets/transfers")
                        .with(user("operator").roles("OPERATOR"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changedBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        String insufficientReference = "API-XFER-" + java.util.UUID.randomUUID();
        String insufficientBody = """
                {"sourceWalletId":"%s","destinationWalletId":"%s","amountMinor":8000,
                 "transactionReference":"%s","reference":"insufficient-funds-test"}
                """.formatted(source, destination, insufficientReference);
        mockMvc.perform(post("/api/v1/wallets/transfers")
                        .with(user("operator").roles("OPERATOR"))
                        .header("Idempotency-Key", "api-transfer-" + java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(insufficientBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(walletsAndBalances()).contains(Map.entry("demo:shubham", 7_500L));
        assertThat(walletsAndBalances()).contains(Map.entry("demo:ashwin", 12_500L));
        assertThat(count("SELECT count(*) FROM public.journal_transactions "
                + "WHERE transaction_reference = '" + transactionReference + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.journal_transactions "
                + "WHERE transaction_reference = '" + insufficientReference + "'")).isZero();
    }

    private java.util.UUID walletId(String ownerReference) throws SQLException {
        try (Connection connection = migratorConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE ledger_owner");
            }
            try (var query = connection.prepareStatement(
                    "SELECT id FROM public.wallets WHERE owner_reference = ? AND currency_code = 'INR'")) {
                query.setString(1, ownerReference);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Expected demo wallet was not provisioned");
                    return rows.getObject(1, java.util.UUID.class);
                }
            }
        }
    }

    private String provisioningSql() throws IOException {
        try (var input = getClass().getResourceAsStream("/db/local-review/provision-demo-wallets.sql")) {
            if (input == null) {
                throw new IOException("Demo wallet provisioning SQL is missing from the classpath");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private org.testcontainers.containers.Container.ExecResult runProvisioner(String sql, long amountPaise)
            throws IOException, InterruptedException {
        String executableSql = sql.replace(":'initial_balance_paise'", "'" + amountPaise + "'");
        return postgres.execInContainer("psql", "-U", "postgres", "-d", "ledger",
                "-v", "ON_ERROR_STOP=1", "-c", executableSql);
    }

    private long count(String sql) {
        try (Connection connection = migratorConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE ledger_owner");
            }
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    throw new IllegalStateException("Count query returned no result");
                }
                return rows.getLong(1);
            } catch (SQLException exception) {
                throw new IllegalStateException("Test-only demo wallet count query failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Test-only ledger owner connection failed", exception);
        }
    }

    private List<Map.Entry<String, Long>> walletsAndBalances() throws SQLException {
        try (Connection connection = migratorConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE ledger_owner");
            }
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT w.owner_reference,
                                COALESCE(sum(CASE e.direction
                                    WHEN 'CREDIT' THEN e.amount_minor::numeric
                                    ELSE -e.amount_minor::numeric END), 0)
                         FROM public.wallets w
                         LEFT JOIN public.journal_entries e ON e.account_id = w.account_id
                         WHERE w.owner_reference LIKE 'demo:%'
                         GROUP BY w.owner_reference
                         """)) {
                var balances = new java.util.ArrayList<Map.Entry<String, Long>>();
                while (rows.next()) {
                    balances.add(Map.entry(rows.getString(1), rows.getBigDecimal(2).longValueExact()));
                }
                return balances;
            }
        }
    }

    private void asLedgerOwner(SqlWork work) throws SQLException {
        try (Connection connection = migratorConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE ledger_owner");
            }
            work.run(connection);
            connection.commit();
        }
    }

    private Connection migratorConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "ledger_migrator", "local_migrator_password");
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }
}
