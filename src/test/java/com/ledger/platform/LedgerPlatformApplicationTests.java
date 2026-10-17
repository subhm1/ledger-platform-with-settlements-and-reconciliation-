
package com.ledger.platform;

import org.flywaydb.core.Flyway;
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
        }
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
}
