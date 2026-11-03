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
import org.springframework.http.MediaType;

import org.springframework.test.context.DynamicPropertyRegistry;

import org.springframework.test.context.DynamicPropertySource;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.web.context.WebApplicationContext;

import org.testcontainers.junit.jupiter.Container;

import org.testcontainers.junit.jupiter.Testcontainers;

import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.dao.DataAccessException;

import java.sql.Connection;

import java.sql.DriverManager;

import java.sql.Statement;

import java.util.UUID;
import java.util.List;

import java.time.OffsetDateTime;

import java.util.concurrent.Callable;

import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;

import java.util.concurrent.Future;

import java.util.concurrent.TimeUnit;


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
    private WebApplicationContext webApplicationContext;


    @Autowired

    private ObjectMapper objectMapper;


    @Autowired

    private Flyway flyway;


    @DynamicPropertySource

    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("ledger.security.username", () -> "operator");
        registry.add("ledger.security.password", () -> "test-only-long-password-123456789");

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


    @Test
    void financialApiRequiresOperatorAuthenticationAndRole() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/transactions").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/journal-transactions").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/transactions").with(user("reader").roles("READER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/transactions"))
                .andExpect(status().isOk());
    }

    @Test
    void retiredProviderEndpointsAreUnavailable() throws Exception {
        mockMvc.perform(get("/api/v1/payment-attempts"))
                .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/payment-attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

    }


    @BeforeEach

    void seedLedgerAccounts() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .defaultRequest(get("/").with(user("operator").roles("OPERATOR")))
                .build();

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
                INSERT INTO public.currencies
                    (code, minor_unit_exponent, enabled)
                VALUES ('USD', 2, TRUE)
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

        return post(scope, key, reference, entries, reversalOf,
                null, "Ledger integration test");
    }

    private UUID post(

            String scope,

            String key,

            String reference,

            String entries,

            UUID reversalOf,

            String authorizedBy,

            String reason) {

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

                reason,

                entries,

                reversalOf == null ? null : reversalOf.toString(),

                authorizedBy,

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

    private long migratorQueryForLong(String sql, Object... args) throws Exception {
        String url = postgres.getJdbcUrl() + "&options=-c%20TimeZone=Asia/Kolkata";
        try (Connection connection = DriverManager.getConnection(
                url, "ledger_migrator", "local_migrator_password");
             java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            try (Statement roleStatement = connection.createStatement()) {
                roleStatement.execute("SET ROLE ledger_owner");
            }
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
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


    private UUID createWallet(String ownerReference) {

        return jdbcTemplate.queryForObject(

                "SELECT public.create_wallet(?, ?)",

                UUID.class,

                ownerReference,

                "INR"

        );

    }


    private String walletField(UUID walletId, String sql) {

        String url = postgres.getJdbcUrl()

                + "&options=-c%20TimeZone=Asia/Kolkata";


        try (Connection connection = DriverManager.getConnection(

                    url, "ledger_migrator", "local_migrator_password")) {

            connection.setAutoCommit(true);


            try (Statement statement = connection.createStatement()) {

                statement.execute("SET ROLE ledger_owner");

            }


            try (java.sql.PreparedStatement query =

                         connection.prepareStatement(sql)) {

                query.setObject(1, walletId);


                try (java.sql.ResultSet result = query.executeQuery()) {

                    if (!result.next()) {

                        throw new IllegalStateException(

                                "Wallet inspection query returned no row");

                    }

                    return result.getString(1);

                }

            }

        } catch (java.sql.SQLException exception) {

            throw new IllegalStateException(

                    "Test-only wallet inspection failed", exception);

        }

    }


    private UUID walletAccount(UUID walletId) {

        return UUID.fromString(walletField(

                walletId,

                "SELECT account_id::text FROM public.wallets WHERE id = ?"

        ));

    }


    private long walletBalance(UUID walletId) {

        String url = postgres.getJdbcUrl()

                + "&options=-c%20TimeZone=Asia/Kolkata";


        try (Connection connection = DriverManager.getConnection(

                    url, "ledger_migrator", "local_migrator_password")) {

            try (Statement statement = connection.createStatement()) {

                statement.execute("SET ROLE ledger_owner");

            }


            try (java.sql.PreparedStatement query = connection.prepareStatement(

                    """

                    SELECT COALESCE(SUM(

                        CASE e.direction

                            WHEN 'CREDIT' THEN e.amount_minor::NUMERIC

                            ELSE -e.amount_minor::NUMERIC

                        END

                    ), 0)

                    FROM public.journal_entries e

                    WHERE e.account_id = (

                        SELECT w.account_id

                        FROM public.wallets w

                        WHERE w.id = ?

                    )

                    """)) {

                query.setObject(1, walletId);


                try (java.sql.ResultSet result = query.executeQuery()) {

                    result.next();

                    return result.getBigDecimal(1).longValueExact();

                }

            }

        } catch (java.sql.SQLException exception) {

            throw new IllegalStateException(

                    "Test-only wallet balance inspection failed", exception);

        }

    }


    private void fundWallet(UUID walletId, long amount) {

        String entries = """

            [

              {"account_id":"%s","direction":"DEBIT","amount_minor":%d},

              {"account_id":"%s","direction":"CREDIT","amount_minor":%d}

            ]

            """.formatted(

                ASSET_ACCOUNT,

                amount,

                walletAccount(walletId),

                amount

            );


        post(

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                entries,

                null

        );

    }


    private UUID transfer(

            String scope,

            String key,

            String reference,

            UUID source,

            UUID destination,

            long amount,

            String description) {

        return jdbcTemplate.queryForObject(

                """

                SELECT public.transfer_between_wallets(

                    ?, ?, ?, ?, ?, ?, ?

                )

                """,

                UUID.class,

                scope,

                key,

                reference,

                source,

                destination,

                amount,

                description

        );

    }


    @Test

    void walletCreationCreatesCustomerWalletAccount() {

        UUID walletId = createWallet("owner-" + UUID.randomUUID());


        assertThat(walletField(

                walletId,

                "SELECT status FROM public.wallets WHERE id = ?"

        )).isEqualTo("ACTIVE");


        assertThat(walletField(

                walletId,

                "SELECT account_role FROM public.accounts WHERE id = (SELECT account_id FROM public.wallets WHERE id = ?)"

        )).isEqualTo("CUSTOMER_WALLET");


        assertThat(walletBalance(walletId)).isZero();

    }


    @Test

    void successfulTransferMovesFundsBetweenWallets() {

        UUID source = createWallet("source-" + UUID.randomUUID());

        UUID destination = createWallet("destination-" + UUID.randomUUID());

        fundWallet(source, 500);


        transfer(

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                source,

                destination,

                200,

                "Wallet transfer"

        );


        assertThat(walletBalance(source)).isEqualTo(300);

        assertThat(walletBalance(destination)).isEqualTo(200);

    }


    @Test

    void transferRejectsInsufficientFunds() {

        UUID source = createWallet("source-" + UUID.randomUUID());

        UUID destination = createWallet("destination-" + UUID.randomUUID());


        assertThatThrownBy(() -> transfer(

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                source,

                destination,

                1,

                "Insufficient funds test"

        )).isInstanceOf(DataAccessException.class)

          .hasMessageContaining("Insufficient wallet funds");


        assertThat(walletBalance(source)).isZero();

        assertThat(walletBalance(destination)).isZero();

    }


    @Test

    void idempotentRetrySucceedsAfterSourceFundsAreExhausted() {

        UUID source = createWallet("source-" + UUID.randomUUID());

        UUID destination = createWallet("destination-" + UUID.randomUUID());

        fundWallet(source, 100);


        String scope = UUID.randomUUID().toString();

        String key = UUID.randomUUID().toString();

        String reference = UUID.randomUUID().toString();


        UUID first = transfer(

                scope, key, reference, source, destination, 100,

                "Idempotent wallet transfer"

        );


        UUID retry = transfer(

                scope, key, reference, source, destination, 100,

                "Idempotent wallet transfer"

        );


        assertThat(retry).isEqualTo(first);

        assertThat(walletBalance(source)).isZero();

        assertThat(walletBalance(destination)).isEqualTo(100);

    }


    @Test

    void directPostingCannotOverdrawWallet() {

        UUID walletId = createWallet("owner-" + UUID.randomUUID());


        String entries = """

            [

              {"account_id":"%s","direction":"DEBIT","amount_minor":100},

              {"account_id":"%s","direction":"CREDIT","amount_minor":100}

            ]

            """.formatted(walletAccount(walletId), ASSET_ACCOUNT);


        assertThatThrownBy(() -> post(

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                entries,

                null

        )).isInstanceOf(DataAccessException.class)

          .hasMessageContaining("Insufficient wallet funds");


        assertThat(walletBalance(walletId)).isZero();

    }


    @Test

    void concurrentTransfersCannotOverdrawWallet() throws Exception {

        UUID source = createWallet("concurrent-source-" + UUID.randomUUID());

        UUID destinationOne = createWallet("concurrent-destination-one-" + UUID.randomUUID());

        UUID destinationTwo = createWallet("concurrent-destination-two-" + UUID.randomUUID());

        fundWallet(source, 100);


        ExecutorService executor = Executors.newFixedThreadPool(2);

        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);


        Callable<Boolean> transferOne = () -> {

            ready.countDown();

            start.await();

            try {

                transfer(UUID.randomUUID().toString(), UUID.randomUUID().toString(),

                        UUID.randomUUID().toString(), source, destinationOne, 80,

                        "Concurrent wallet transfer one");

                return true;

            } catch (DataAccessException exception) {

                if (exception.getMessage() != null

                        && exception.getMessage().contains("Insufficient wallet funds")) {

                    return false;

                }

                throw exception;

            }

        };


        Callable<Boolean> transferTwo = () -> {

            ready.countDown();

            start.await();

            try {

                transfer(UUID.randomUUID().toString(), UUID.randomUUID().toString(),

                        UUID.randomUUID().toString(), source, destinationTwo, 80,

                        "Concurrent wallet transfer two");

                return true;

            } catch (DataAccessException exception) {

                if (exception.getMessage() != null

                        && exception.getMessage().contains("Insufficient wallet funds")) {

                    return false;

                }

                throw exception;

            }

        };


        try {

            Future<Boolean> first = executor.submit(transferOne);

            Future<Boolean> second = executor.submit(transferTwo);


            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

            start.countDown();


            boolean firstSucceeded = first.get(30, TimeUnit.SECONDS);

            boolean secondSucceeded = second.get(30, TimeUnit.SECONDS);


            assertThat(firstSucceeded).isNotEqualTo(secondSucceeded);

            assertThat(walletBalance(source)).isEqualTo(20);

            assertThat(walletBalance(destinationOne) + walletBalance(destinationTwo))

                    .isEqualTo(80);

        } finally {

            start.countDown();

            executor.shutdownNow();

        }

    }

    @Test
    void concurrentPostingLoadPreservesPersistedAccountingInvariants() throws Exception {
        int requestCount = 32;
        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(concurrency);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Future<UUID>> futures = new java.util.ArrayList<>();
        String scope = "phase5-load-" + UUID.randomUUID();
        try {
            for (int i = 0; i < requestCount; i++) {
                final int requestNumber = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return post(scope, "key-" + requestNumber,
                            "TX-LOAD-" + UUID.randomUUID(), balancedEntries(), null);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            java.util.Set<UUID> transactionIds = new java.util.HashSet<>();
            for (Future<UUID> future : futures) {
                transactionIds.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(transactionIds).hasSize(requestCount);
            assertThat(migratorQueryForLong(
                    "SELECT COUNT(*) FROM public.idempotency_records WHERE scope = ?",
                    scope)).isEqualTo((long) requestCount);
            assertThat(migratorQueryForLong("""
                    SELECT COUNT(*) FROM (
                        SELECT jt.id
                        FROM public.journal_transactions jt
                        LEFT JOIN public.journal_entries je ON je.transaction_id = jt.id
                        WHERE jt.id IN (SELECT transaction_id FROM public.idempotency_records WHERE scope = ?)
                        GROUP BY jt.id
                        HAVING COUNT(je.id) < 2
                            OR COALESCE(SUM(je.amount_minor) FILTER (WHERE je.direction = 'DEBIT'), 0)
                               <> COALESCE(SUM(je.amount_minor) FILTER (WHERE je.direction = 'CREDIT'), 0)
                    ) invalid_postings
                    """, scope)).isZero();
            assertThat(migratorQueryForLong("""
                    SELECT COUNT(*) FROM public.journal_entries je
                    JOIN public.journal_transactions jt ON jt.id = je.transaction_id
                    WHERE jt.id IN (SELECT transaction_id FROM public.idempotency_records WHERE scope = ?)
                      AND je.currency_code <> jt.currency_code
                    """, scope)).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentReversalAttemptsProduceExactlyOneReversal() throws Exception {
        String reference = "TX-REVERSAL-RACE-" + UUID.randomUUID();
        UUID original = post(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                reference, balancedEntries(), null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Callable<Boolean> reverse = () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                post(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        "REV-" + UUID.randomUUID(), reversalEntries(), original);
                return true;
            } catch (DataAccessException exception) {
                if (containsThrowableMessage(exception, "Original transaction has already been reversed")) {
                    return false;
                }
                throw exception;
            }
        };
        try {
            Future<Boolean> first = executor.submit(reverse);
            Future<Boolean> second = executor.submit(reverse);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat((int) (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.journal_transactions WHERE reversal_of_transaction_id = ?",
                    Long.class, original)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.journal_entries WHERE transaction_id IN "
                            + "(SELECT id FROM public.journal_transactions "
                            + "WHERE reversal_of_transaction_id = ?)", Long.class, original)).isEqualTo(2L);
        } finally {
            start.countDown();
            executor.shutdownNow();
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

                .isEqualTo("11");

    }

    @Test
    void healthEndpointReportsDatabaseReadiness() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void phaseFiveSettlementLookupPlanUsesBatchReferenceIndex() throws Exception {
        int syntheticRows = 3_000;
        String source = "SRC-PHASE5-PLAN-" + UUID.randomUUID();
        String report = "RPT-PHASE5-PLAN-" + UUID.randomUUID();
        String jsonRows = jdbcTemplate.queryForObject("""
                SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                    'row_number', n + 1,
                    'raw_row_text', 'synthetic-row-' || n,
                    'raw_row', pg_catalog.jsonb_build_object('synthetic', true),
                    'external_transaction_id', 'EXT-PLAN-' || n,
                    'transaction_reference', 'TX-PLAN-' || n,
                    'currency_code', 'INR',
                    'gross_amount_minor', 100,
                    'fee_amount_minor', 0,
                    'adjustment_amount_minor', 0,
                    'net_amount_minor', 100,
                    'validation_status', 'VALID',
                    'validation_errors', '[]'::jsonb
                ) ORDER BY n)::text
                FROM pg_catalog.generate_series(1, ?) AS gs(n)
                """, String.class, syntheticRows);
        UUID batch = importSettlement(
                source, report, "7".repeat(64), jsonRows,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));

        String url = postgres.getJdbcUrl() + "&options=-c%20TimeZone=Asia/Kolkata";
        try (Connection connection = DriverManager.getConnection(
                    url, "ledger_migrator", "local_migrator_password");
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE ledger_owner");
            statement.execute("ANALYZE public.settlement_records");
        }

        String jsonPlan = jdbcTemplate.queryForObject("""
                EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
                SELECT id
                FROM public.settlement_records
                WHERE batch_id = ? AND transaction_reference = ?
                """, String.class, batch, "TX-PLAN-" + syntheticRows);
        JsonNode planRoot = objectMapper.readTree(jsonPlan).get(0);
        String planText = planRoot.get("Plan").toString();
        double executionMillis = planRoot.get("Execution Time").asDouble();
        assertThat(planText).contains("settlement_records_reference_idx");
        assertThat(planRoot.get("Plan").get("Actual Rows").asInt()).isEqualTo(1);
        System.out.printf(
                "PHASE5_QUERY_PLAN dataset_rows=%d execution_ms=%.3f buffers=%s index=%s%n",
                syntheticRows,
                executionMillis,
                planRoot.get("Plan").get("Shared Hit Blocks"),
                "settlement_records_reference_idx");
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
    void concurrentIdenticalPostingRequestsCreateOneFinancialEffect() throws Exception {
        String scope = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        String reference = "TX-CONCURRENT-IDEMP-" + UUID.randomUUID();
        String body = requestBody(scope, key, reference, 100, 100);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Callable<String> request = () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/journal-transactions")
                            .contentType("application/json").content(body))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        };
        try {
            Future<String> first = executor.submit(request);
            Future<String> second = executor.submit(request);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            String firstId = objectMapper.readTree(first.get(30, TimeUnit.SECONDS))
                    .get("transactionId").asText();
            String secondId = objectMapper.readTree(second.get(30, TimeUnit.SECONDS))
                    .get("transactionId").asText();
            assertThat(secondId).isEqualTo(firstId);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.journal_transactions WHERE transaction_reference = ?",
                    Long.class, reference)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.journal_entries WHERE transaction_id = ?",
                    Long.class, UUID.fromString(firstId))).isEqualTo(2L);
            assertThat(migratorQueryForLong(
                    "SELECT COUNT(*) FROM public.idempotency_records WHERE scope = ? AND idempotency_key = ?",
                    scope, key)).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentConflictingPostingRequestsHaveOneWinnerAndOneConflict() throws Exception {
        String scope = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        String reference = "TX-CONCURRENT-CONFLICT-" + UUID.randomUUID();
        String[] bodies = {
                requestBody(scope, key, reference, 100, 100),
                requestBody(scope, key, reference, 200, 200)
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Callable<Integer> requestA = () -> concurrentPostingStatus(ready, start, bodies[0]);
        Callable<Integer> requestB = () -> concurrentPostingStatus(ready, start, bodies[1]);
        try {
            Future<Integer> first = executor.submit(requestA);
            Future<Integer> second = executor.submit(requestB);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int[] statuses = {first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)};
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.journal_transactions WHERE transaction_reference = ?",
                    Long.class, reference)).isEqualTo(1L);
            assertThat(migratorQueryForLong(
                    "SELECT COUNT(*) FROM public.idempotency_records WHERE scope = ? AND idempotency_key = ?",
                    scope, key)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT transaction_id FROM public.journal_entries "
                            + "WHERE transaction_id = (SELECT id FROM public.journal_transactions "
                            + "WHERE transaction_reference = ?) GROUP BY transaction_id "
                            + "HAVING SUM(amount_minor) FILTER (WHERE direction = 'DEBIT') "
                            + "<> SUM(amount_minor) FILTER (WHERE direction = 'CREDIT')) unbalanced",
                    Long.class, reference)).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private int concurrentPostingStatus(
            java.util.concurrent.CountDownLatch ready,
            java.util.concurrent.CountDownLatch start,
            String body) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/journal-transactions")
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void apiRetryAfterUnknownClientOutcomeReturnsCommittedPosting() throws Exception {
        String scope = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        String reference = "TX-RETRY-" + UUID.randomUUID();
        String retryEntries = """
            [
              {"account_id":"11111111-1111-4111-8111-111111111111","direction":"DEBIT","amount_minor":125},
              {"account_id":"22222222-2222-4222-8222-222222222222","direction":"CREDIT","amount_minor":125}
            ]
            """;
        UUID committed = post(scope, key, reference, retryEntries, null,
                null, "API integration test");
        String body = requestBody(scope, key, reference, 125, 125);

        String retry = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/journal-transactions")
                                .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(UUID.fromString(objectMapper.readTree(retry)
                .get("transactionId").asText())).isEqualTo(committed);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.journal_transactions WHERE transaction_reference = ?",
                Long.class, reference)).isEqualTo(1L);
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


    @Test

    void invalidCurrencyReturns400WithoutPersistingTransaction() throws Exception {

        String reference = UUID.randomUUID().toString();

        String body = requestBody(

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                reference, 100, 100)

                .replace("\"currencyCode\": \"INR\"",

                         "\"currencyCode\": \"inr\"");


        mockMvc.perform(

                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders

                                .post("/api/v1/journal-transactions")

                                .contentType("application/json")

                                .content(body))

                .andExpect(status().isBadRequest())

                .andExpect(jsonPath("$.code").value("INVALID_CURRENCY_CODE"));


        Long count = jdbcTemplate.queryForObject(

                "SELECT count(*) FROM public.journal_transactions WHERE transaction_reference = ?",

                Long.class, reference);

        assertThat(count).isZero();

    }


    @Test

    void zeroEntryAmountReturns400WithoutPersistingTransaction() throws Exception {

        String reference = UUID.randomUUID().toString();

        String body = requestBody(

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                reference, 100, 100)

                .replace("\"amountMinor\": 100",

                         "\"amountMinor\": 0");


        mockMvc.perform(

                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders

                                .post("/api/v1/journal-transactions")

                                .contentType("application/json")

                                .content(body))

                .andExpect(status().isBadRequest())

                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));


        Long count = jdbcTemplate.queryForObject(

                "SELECT count(*) FROM public.journal_transactions WHERE transaction_reference = ?",

                Long.class, reference);

        assertThat(count).isZero();

    }


    @Test

    void settlementImportIsIdempotent() {

        String source = "SRC-" + UUID.randomUUID();

        String report = "RPT-" + UUID.randomUUID();

        String hash = "a".repeat(64);

        String rows = """

            [{"row_number":2,"raw_row_text":"test","raw_row":{},

              "external_transaction_id":"EXT-1","transaction_reference":"TX-1",

              "currency_code":"INR","gross_amount_minor":100,"fee_amount_minor":0,

              "adjustment_amount_minor":0,"net_amount_minor":100,

              "validation_status":"VALID","validation_errors":[]}]

            """;


        UUID first = importSettlement(source, report, hash, rows);

        UUID second = importSettlement(source, report, hash, rows);


        assertThat(second).isEqualTo(first);

        assertThat(jdbcTemplate.queryForObject(

                "SELECT COUNT(*) FROM public.settlement_records WHERE batch_id = ?",

                Long.class, first)).isEqualTo(1L);

    }

    @Test
    void concurrentSettlementImportsWithSameHashReturnOneBatch() throws Exception {
        String source = "SRC-RACE-" + UUID.randomUUID();
        String report = "RPT-RACE-" + UUID.randomUUID();
        String hash = "b".repeat(64);
        String rows = "[{\"row_number\":2,\"raw_row_text\":\"x\",\"raw_row\":{},"
                + "\"external_transaction_id\":\"EXT\",\"transaction_reference\":\"TX-RACE\","
                + "\"currency_code\":\"INR\",\"gross_amount_minor\":100,"
                + "\"fee_amount_minor\":0,\"adjustment_amount_minor\":0,"
                + "\"net_amount_minor\":100,\"validation_status\":\"VALID\","
                + "\"validation_errors\":[]}]";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Callable<UUID> attempt = () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return importSettlement(source, report, hash, rows);
        };
        try {
            Future<UUID> first = executor.submit(attempt);
            Future<UUID> second = executor.submit(attempt);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.settlement_batches WHERE source_code = ? AND report_id = ?",
                    Long.class, source, report)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.settlement_records WHERE batch_id = "
                            + "(SELECT id FROM public.settlement_batches WHERE source_code = ? AND report_id = ?)",
                    Long.class, source, report)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSettlementImportsWithDifferentHashesConflictAtomically() throws Exception {
        String source = "SRC-RACE-" + UUID.randomUUID();
        String report = "RPT-RACE-" + UUID.randomUUID();
        String rows = "[{\"row_number\":2,\"raw_row_text\":\"x\",\"raw_row\":{},"
                + "\"external_transaction_id\":\"EXT\",\"transaction_reference\":\"TX-RACE\","
                + "\"currency_code\":\"INR\",\"gross_amount_minor\":100,"
                + "\"fee_amount_minor\":0,\"adjustment_amount_minor\":0,"
                + "\"net_amount_minor\":100,\"validation_status\":\"VALID\","
                + "\"validation_errors\":[]}]";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Callable<Boolean> attemptA = () -> concurrentImportAttempt(
                ready, start, source, report, "a".repeat(64), rows);
        Callable<Boolean> attemptB = () -> concurrentImportAttempt(
                ready, start, source, report, "c".repeat(64), rows);
        try {
            Future<Boolean> first = executor.submit(attemptA);
            Future<Boolean> second = executor.submit(attemptB);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat((int) (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.settlement_batches WHERE source_code = ? AND report_id = ?",
                    Long.class, source, report)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.settlement_records WHERE batch_id = "
                            + "(SELECT id FROM public.settlement_batches WHERE source_code = ? AND report_id = ?)",
                    Long.class, source, report)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean concurrentImportAttempt(
            java.util.concurrent.CountDownLatch ready,
            java.util.concurrent.CountDownLatch start,
            String source, String report, String hash, String rows) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            importSettlement(source, report, hash, rows);
            return true;
        } catch (DataAccessException exception) {
            if (containsThrowableMessage(exception,
                    "Report identity already exists with different content")) {
                return false;
            }
            throw exception;
        }
    }

    private boolean containsThrowableMessage(Throwable exception, String expected) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
        }
        return false;
    }


    @Test

    void settlementImportRejectsDifferentContentForExistingIdentity() {

        String source = "SRC-" + UUID.randomUUID();

        String report = "RPT-" + UUID.randomUUID();

        String rows = """

            [{"row_number":2,"raw_row_text":"test","raw_row":{},

              "external_transaction_id":"EXT-2","transaction_reference":"TX-2",

              "currency_code":"INR","gross_amount_minor":100,"fee_amount_minor":0,

              "adjustment_amount_minor":0,"net_amount_minor":100,

              "validation_status":"VALID","validation_errors":[]}]

            """;


        importSettlement(source, report, "a".repeat(64), rows);


        assertThatThrownBy(() ->

                importSettlement(source, report, "b".repeat(64), rows))

                .isInstanceOf(DataAccessException.class);

    }


    @Test

    void settlementImportPreservesInvalidRows() {

        String source = "SRC-" + UUID.randomUUID();

        String report = "RPT-" + UUID.randomUUID();

        String rows = """

            [{"row_number":2,"raw_row_text":"invalid row","raw_row":{},

              "external_transaction_id":"EXT-BAD","transaction_reference":"TX-BAD",

              "currency_code":"INR","gross_amount_minor":-1,"fee_amount_minor":0,

              "adjustment_amount_minor":0,"net_amount_minor":-1,

              "validation_status":"INVALID",

              "validation_errors":["NEGATIVE_GROSS_AMOUNT","NEGATIVE_NET_AMOUNT"]}]

            """;


        UUID batch = importSettlement(source, report, "c".repeat(64), rows);


        assertThat(jdbcTemplate.queryForObject(

                "SELECT validation_status FROM public.settlement_records WHERE batch_id = ?",

                String.class, batch)).isEqualTo("INVALID");

        assertThat(jdbcTemplate.queryForObject(

                "SELECT pg_catalog.jsonb_array_length(validation_errors) "

                        + "FROM public.settlement_records WHERE batch_id = ?",

                Integer.class, batch)).isEqualTo(2);

    }


    @Test

    void reconciliationRerunsPreserveHistoryAndDoNotChangeLedgerEntries() {

        String reference = "TX-" + UUID.randomUUID();

        String externalId = "EXT-" + UUID.randomUUID();


        UUID transactionId = post(

                UUID.randomUUID().toString(),

                UUID.randomUUID().toString(),

                reference,

                balancedEntries(),

                null);


        String ledgerBefore = ledgerSnapshot();

        Long entriesBefore = jdbcTemplate.queryForObject(

                "SELECT COUNT(*) FROM public.journal_entries WHERE transaction_id = ?",

                Long.class, transactionId);


        String rows = """

            [{"row_number":2,"raw_row_text":"test","raw_row":{},

              "external_transaction_id":"%s","transaction_reference":"%s",

              "currency_code":"INR","gross_amount_minor":100,"fee_amount_minor":0,

              "adjustment_amount_minor":0,"net_amount_minor":100,

              "validation_status":"VALID","validation_errors":[]}]

            """.formatted(externalId, reference);


        UUID batch = importSettlement(

                "SRC-" + UUID.randomUUID(),

                "RPT-" + UUID.randomUUID(),

                "d".repeat(64),

                rows);


        UUID run1 = jdbcTemplate.queryForObject(

                "SELECT public.reconcile_settlement_batch(?, ?)",

                UUID.class, batch, "rules-v1");

        UUID run2 = jdbcTemplate.queryForObject(

                "SELECT public.reconcile_settlement_batch(?, ?)",

                UUID.class, batch, "rules-v2");


        assertThat(run2).isNotEqualTo(run1);

        assertThat(jdbcTemplate.queryForObject(

                "SELECT COUNT(*) FROM public.reconciliation_runs WHERE batch_id = ?",

                Long.class, batch)).isEqualTo(2L);

        assertThat(jdbcTemplate.queryForObject(

                "SELECT outcome FROM public.reconciliation_results "

                        + "WHERE run_id = ? AND external_record_id IS NOT NULL",

                String.class, run1)).isEqualTo("MATCHED");

        assertThat(jdbcTemplate.queryForObject(

                "SELECT outcome FROM public.reconciliation_results "

                        + "WHERE run_id = ? AND external_record_id IS NOT NULL",

                String.class, run2)).isEqualTo("MATCHED");


        assertThat(jdbcTemplate.queryForObject(

                "SELECT COUNT(*) FROM public.journal_entries WHERE transaction_id = ?",

                Long.class, transactionId)).isEqualTo(entriesBefore);
        assertThat(ledgerSnapshot()).isEqualTo(ledgerBefore);

    }

    @Test
    void reconciliationApiCreatesPersistedRunAndDoesNotMutateLedger() throws Exception {
        String source = "SRC-API-" + UUID.randomUUID();
        String report = "RPT-API-" + UUID.randomUUID();
        String rows = """
                [{"row_number":2,"raw_row_text":"evidence","raw_row":{},
                  "external_transaction_id":"EXT-API","transaction_reference":"TX-API",
                  "currency_code":"INR","gross_amount_minor":100,"fee_amount_minor":0,
                  "adjustment_amount_minor":0,"net_amount_minor":100,
                  "validation_status":"VALID","validation_errors":[]}]
                """;
        UUID batchId = importSettlement(source, report, "e".repeat(64), rows);
        String ledgerBefore = ledgerSnapshot();

        String response = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/settlement-batches/{batchId}/reconciliation-runs", batchId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").value(batchId.toString()))
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(objectMapper.readTree(response).get("runId").asText());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM public.reconciliation_runs WHERE id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT outcome FROM public.reconciliation_results "
                        + "WHERE run_id = ? AND external_record_id IS NOT NULL", String.class, runId))
                .isEqualTo("EXTERNAL_ONLY");
        assertThat(ledgerSnapshot()).isEqualTo(ledgerBefore);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/settlement-batches/{batchId}/reconciliation-runs", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void settlementImportApiReportsIdempotentRepeatAccurately() throws Exception {
        String source = "SRC-IMPORT-API-" + UUID.randomUUID();
        String report = "RPT-IMPORT-API-" + UUID.randomUUID();
        String csv = "external_transaction_id,transaction_reference,currency_code,"
                + "gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"
                + "EXT-IMPORT,TX-IMPORT,INR,100,0,0,100\n";
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/settlement-batches")
                .file(new org.springframework.mock.web.MockMultipartFile(
                        "file", "settlement.csv", "text/csv",
                        csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("sourceCode", source)
                .param("reportId", report)
                .param("reportingPeriodStart", "2026-10-24T00:00:00+05:30")
                .param("reportingPeriodEnd", "2026-10-26T00:00:00+05:30");

        String firstResponse = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andReturn().getResponse().getContentAsString();
        String batchId = objectMapper.readTree(firstResponse).get("batchId").asText();
        String repeatedResponse = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_IMPORTED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(repeatedResponse).get("batchId").asText())
                .isEqualTo(batchId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.settlement_batches WHERE source_code = ? AND report_id = ?",
                Long.class, source, report)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.settlement_records WHERE batch_id = ?",
                Long.class, UUID.fromString(batchId))).isEqualTo(1L);
    }

    private String ledgerSnapshot() {
        return jdbcTemplate.queryForObject("""
                SELECT pg_catalog.jsonb_build_object(
                    'transactions', COALESCE((
                        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                            'id', jt.id,
                            'reference', jt.transaction_reference,
                            'currency', jt.currency_code,
                            'description', jt.description,
                            'posted_at', jt.posted_at,
                            'reversal_of', jt.reversal_of_transaction_id,
                            'entries', COALESCE((
                                SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                                    'id', je.id,
                                    'account_id', je.account_id,
                                    'direction', je.direction,
                                    'amount_minor', je.amount_minor
                                ) ORDER BY je.id)
                                FROM public.journal_entries je
                                WHERE je.transaction_id = jt.id
                            ), '[]'::jsonb)
                        ) ORDER BY jt.id)
                        FROM public.journal_transactions jt
                    ), '[]'::jsonb),
                    'account_balances', COALESCE((
                        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                            'account_id', a.id,
                            'currency', a.currency_code,
                            'status', a.status,
                            'balance', COALESCE((
                                SELECT SUM(CASE je.direction
                                    WHEN 'CREDIT' THEN je.amount_minor
                                    ELSE -je.amount_minor END)
                                FROM public.journal_entries je
                                WHERE je.account_id = a.id
                            ), 0)
                        ) ORDER BY a.id)
                        FROM public.accounts a
                    ), '[]'::jsonb)
                )::text
                """, String.class);
    }

    @Test
    void concurrentReconciliationCreatesSequentialHistoricalRuns() throws Exception {
        String rows = "[{\"row_number\":2,\"raw_row_text\":\"invalid\",\"raw_row\":{},"
                + "\"external_transaction_id\":\"EXT-CONCURRENT\","
                + "\"transaction_reference\":\"TX-CONCURRENT\",\"currency_code\":\"INR\","
                + "\"gross_amount_minor\":-1,\"fee_amount_minor\":0,"
                + "\"adjustment_amount_minor\":0,\"net_amount_minor\":-1,"
                + "\"validation_status\":\"INVALID\","
                + "\"validation_errors\":[\"NEGATIVE_GROSS_AMOUNT\"]}]";
        UUID batch = importSettlement(
                "SRC-" + UUID.randomUUID(), "RPT-" + UUID.randomUUID(),
                "9".repeat(64), rows);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Callable<UUID> attempt = () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return jdbcTemplate.queryForObject(
                    "SELECT public.reconcile_settlement_batch(?, ?)", UUID.class,
                    batch, "concurrent-rules-v1");
        };
        try {
            Future<UUID> first = executor.submit(attempt);
            Future<UUID> second = executor.submit(attempt);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            UUID firstRun = first.get(20, TimeUnit.SECONDS);
            UUID secondRun = second.get(20, TimeUnit.SECONDS);
            assertThat(firstRun).isNotEqualTo(secondRun);
            assertThat(jdbcTemplate.queryForList(
                    "SELECT run_number FROM public.reconciliation_runs "
                            + "WHERE batch_id = ? ORDER BY run_number", Integer.class, batch))
                    .containsExactly(1, 2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.reconciliation_results "
                            + "WHERE run_id IN (?, ?)", Long.class, firstRun, secondRun))
                    .isEqualTo(2L);
        } finally {
            executor.shutdownNow();
        }
    }


    private UUID importSettlement(
            String source, String report, String hash, String rows) {
        return importSettlement(
                source, report, hash, rows,
                OffsetDateTime.now().minusDays(1),
                OffsetDateTime.now().plusDays(1));
    }

    private UUID importSettlement(
            String source,
            String report,
            String hash,
            String rows,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd) {
        return jdbcTemplate.queryForObject(
                """
                SELECT public.import_settlement_batch(
                    ?, ?, ?, ?, ?, ?, ?::jsonb
                )
                """,
                UUID.class,
                source, report, hash, "integration-test.csv",
                periodStart, periodEnd, rows);
    }


    @Test

    void reconciliationClassifiesAllCoreOutcomes() {

        String matchedRef = "TX-" + UUID.randomUUID();

        String mismatchRef = "TX-" + UUID.randomUUID();

        String internalOnlyRef = "TX-" + UUID.randomUUID();


        post(UUID.randomUUID().toString(), UUID.randomUUID().toString(),

                matchedRef, balancedEntries(), null);

        post(UUID.randomUUID().toString(), UUID.randomUUID().toString(),

                mismatchRef, balancedEntries(), null);

        post(UUID.randomUUID().toString(), UUID.randomUUID().toString(),

                internalOnlyRef, balancedEntries(), null);


        String duplicateId = "DUP-" + UUID.randomUUID();

        String rows = """

            [

              {"row_number":2,"raw_row_text":"matched","raw_row":{},

               "external_transaction_id":"EXT-M","transaction_reference":"%s",

               "currency_code":"INR","gross_amount_minor":100,"fee_amount_minor":0,

               "adjustment_amount_minor":0,"net_amount_minor":100,

               "validation_status":"VALID","validation_errors":[]},

              {"row_number":3,"raw_row_text":"mismatch","raw_row":{},

               "external_transaction_id":"EXT-X","transaction_reference":"%s",

               "currency_code":"INR","gross_amount_minor":99,"fee_amount_minor":0,

               "adjustment_amount_minor":0,"net_amount_minor":99,

               "validation_status":"VALID","validation_errors":[]},

              {"row_number":4,"raw_row_text":"external-only","raw_row":{},

               "external_transaction_id":"EXT-ONLY","transaction_reference":"NO-TX-%s",

               "currency_code":"INR","gross_amount_minor":50,"fee_amount_minor":0,

               "adjustment_amount_minor":0,"net_amount_minor":50,

               "validation_status":"VALID","validation_errors":[]},

              {"row_number":5,"raw_row_text":"duplicate-1","raw_row":{},

               "external_transaction_id":"%s","transaction_reference":"DUP-REF-1-%s",

               "currency_code":"INR","gross_amount_minor":30,"fee_amount_minor":0,

               "adjustment_amount_minor":0,"net_amount_minor":30,

               "validation_status":"VALID","validation_errors":[]},

              {"row_number":6,"raw_row_text":"duplicate-2","raw_row":{},

               "external_transaction_id":"%s","transaction_reference":"DUP-REF-2-%s",

               "currency_code":"INR","gross_amount_minor":30,"fee_amount_minor":0,

               "adjustment_amount_minor":0,"net_amount_minor":30,

               "validation_status":"VALID","validation_errors":[]},

              {"row_number":7,"raw_row_text":"invalid","raw_row":{},

               "external_transaction_id":"EXT-BAD","transaction_reference":"BAD-REF-%s",

               "currency_code":"INR","gross_amount_minor":-1,"fee_amount_minor":0,

               "adjustment_amount_minor":0,"net_amount_minor":-1,

               "validation_status":"INVALID",

               "validation_errors":["NEGATIVE_GROSS_AMOUNT","NEGATIVE_NET_AMOUNT"]}

            ]

            """.formatted(

                matchedRef, mismatchRef, UUID.randomUUID(),

                duplicateId, UUID.randomUUID(), duplicateId, UUID.randomUUID(),

                UUID.randomUUID());


        UUID batch = importSettlement(

                "SRC-" + UUID.randomUUID(),

                "RPT-" + UUID.randomUUID(),

                "e".repeat(64),

                rows);


        UUID run = jdbcTemplate.queryForObject(

                "SELECT public.reconcile_settlement_batch(?, ?)",

                UUID.class, batch, "rules-core-v1");


        assertThat(countOutcome(run, "MATCHED")).isEqualTo(1L);

        assertThat(countOutcome(run, "AMOUNT_MISMATCH")).isEqualTo(1L);

        assertThat(countOutcome(run, "EXTERNAL_ONLY")).isEqualTo(1L);

        assertThat(countOutcome(run, "DUPLICATE_EXTERNAL")).isEqualTo(2L);

        assertThat(countOutcome(run, "INVALID")).isEqualTo(1L);

        assertThat(jdbcTemplate.queryForObject(

        "SELECT COUNT(*) FROM public.reconciliation_results " +

                "WHERE run_id = ? AND outcome = 'INTERNAL_ONLY' " +

                "AND details ->> 'transaction_reference' = ?",

        Long.class, run, internalOnlyRef)).isEqualTo(1L);

        assertThat(jdbcTemplate.queryForObject(

                "SELECT status FROM public.reconciliation_runs WHERE id = ?",

                String.class, run)).isEqualTo("COMPLETED");

    }

    @Test
void reconciliationMatchesOnGrossAmountRegardlessOfFeesAndAdjustments() {
    String[] references = {
        "TX-GROSS-FEE-" + UUID.randomUUID(),
        "TX-GROSS-POS-" + UUID.randomUUID(),
        "TX-GROSS-NEG-" + UUID.randomUUID()
    };

    for (String reference : references) {
        post(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            reference,
            balancedEntries(),
            null
        );
    }

    String rows = """
        [
          {
            "row_number":2,"raw_row_text":"fee","raw_row":{},
            "external_transaction_id":"EXT-FEE",
            "transaction_reference":"%s","currency_code":"INR",
            "gross_amount_minor":100,"fee_amount_minor":5,
            "adjustment_amount_minor":0,"net_amount_minor":95,
            "validation_status":"VALID","validation_errors":[]
          },
          {
            "row_number":3,"raw_row_text":"positive adjustment","raw_row":{},
            "external_transaction_id":"EXT-POS",
            "transaction_reference":"%s","currency_code":"INR",
            "gross_amount_minor":100,"fee_amount_minor":5,
            "adjustment_amount_minor":2,"net_amount_minor":97,
            "validation_status":"VALID","validation_errors":[]
          },
          {
            "row_number":4,"raw_row_text":"negative adjustment","raw_row":{},
            "external_transaction_id":"EXT-NEG",
            "transaction_reference":"%s","currency_code":"INR",
            "gross_amount_minor":100,"fee_amount_minor":5,
            "adjustment_amount_minor":-2,"net_amount_minor":93,
            "validation_status":"VALID","validation_errors":[]
          }
        ]
        """.formatted(references[0], references[1], references[2]);

    UUID batch = importSettlement(
        "SRC-" + UUID.randomUUID(),
        "RPT-" + UUID.randomUUID(),
        "a".repeat(64),
        rows
    );

    UUID run = jdbcTemplate.queryForObject(
        "SELECT public.reconcile_settlement_batch(?, ?)",
        UUID.class,
        batch,
        "rules-gross-matching-v1"
    );

    assertThat(countOutcome(run, "MATCHED")).isEqualTo(3L);
    assertThat(countOutcome(run, "AMOUNT_MISMATCH")).isZero();
    assertThat(countOutcome(run, "INVALID")).isZero();
}


    @Test
    void reconciliationIncludesTransactionAtReportingPeriodStartBoundary() {
        String reference = "TX-START-" + UUID.randomUUID();
        post(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                reference, balancedEntries(), null);

        OffsetDateTime postedAt = jdbcTemplate.queryForObject(
                "SELECT posted_at FROM public.journal_transactions WHERE transaction_reference = ?",
                OffsetDateTime.class, reference);
        String rows = """
                [{"row_number":2,"raw_row_text":"start-boundary","raw_row":{},
                  "external_transaction_id":"EXT-START","transaction_reference":"%s",
                  "currency_code":"INR","gross_amount_minor":100,"fee_amount_minor":0,
                  "adjustment_amount_minor":0,"net_amount_minor":100,
                  "validation_status":"VALID","validation_errors":[]}]
                """.formatted(reference);

        UUID batch = importSettlement(
                "SRC-" + UUID.randomUUID(), "RPT-" + UUID.randomUUID(),
                "f".repeat(64), rows, postedAt, postedAt.plusDays(1));
        UUID run = jdbcTemplate.queryForObject(
                "SELECT public.reconcile_settlement_batch(?, ?)",
                UUID.class, batch, "rules-start-boundary-v1");

        assertThat(countOutcome(run, "MATCHED")).isEqualTo(1L);
        assertThat(countOutcome(run, "EXTERNAL_ONLY")).isZero();
    }

    @Test
    void reconciliationExcludesTransactionAtReportingPeriodEndBoundary() {
        String reference = "TX-END-" + UUID.randomUUID();
        post(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                reference, balancedEntries(), null);

        OffsetDateTime postedAt = jdbcTemplate.queryForObject(
                "SELECT posted_at FROM public.journal_transactions WHERE transaction_reference = ?",
                OffsetDateTime.class, reference);
        String rows = """
                [{"row_number":2,"raw_row_text":"end-boundary","raw_row":{},
                  "external_transaction_id":"EXT-END","transaction_reference":"%s",
                  "currency_code":"INR","gross_amount_minor":100,"fee_amount_minor":0,
                  "adjustment_amount_minor":0,"net_amount_minor":100,
                  "validation_status":"VALID","validation_errors":[]}]
                """.formatted(reference);

        UUID batch = importSettlement(
                "SRC-" + UUID.randomUUID(), "RPT-" + UUID.randomUUID(),
                "1".repeat(64), rows, postedAt.minusDays(1), postedAt);
        UUID run = jdbcTemplate.queryForObject(
                "SELECT public.reconcile_settlement_batch(?, ?)",
                UUID.class, batch, "rules-end-boundary-v1");

        assertThat(countOutcome(run, "MATCHED")).isZero();
        assertThat(countOutcome(run, "EXTERNAL_ONLY")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.reconciliation_results " +
                        "WHERE run_id = ? AND outcome = 'INTERNAL_ONLY' " +
                        "AND details ->> 'transaction_reference' = ?",
                Long.class, run, reference)).isZero();
    }

    @Test
    void reconciliationRequiresExactCurrencyMatch() {
        String reference = "TX-CURRENCY-" + UUID.randomUUID();
        post(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                reference, balancedEntries(), null);
        String rows = """
                [{"row_number":2,"raw_row_text":"currency-mismatch","raw_row":{},
                  "external_transaction_id":"EXT-CURRENCY","transaction_reference":"%s",
                  "currency_code":"USD","gross_amount_minor":100,"fee_amount_minor":0,
                  "adjustment_amount_minor":0,"net_amount_minor":100,
                  "validation_status":"VALID","validation_errors":[]}]
                """.formatted(reference);

        UUID batch = importSettlement(
                "SRC-" + UUID.randomUUID(), "RPT-" + UUID.randomUUID(),
                "2".repeat(64), rows);
        UUID run = jdbcTemplate.queryForObject(
                "SELECT public.reconcile_settlement_batch(?, ?)",
                UUID.class, batch, "rules-currency-v1");

        assertThat(countOutcome(run, "MATCHED")).isZero();
        assertThat(countOutcome(run, "EXTERNAL_ONLY")).isEqualTo(1L);
    }

    @Test
    void reconciliationFlagsDistinctExternalRowsWithSameTransactionReference() {
        String reference = "TX-DUP-REF-" + UUID.randomUUID();
        String rows = """
                [
                  {"row_number":2,"raw_row_text":"duplicate-reference-1","raw_row":{},
                   "external_transaction_id":"EXT-DUP-1","transaction_reference":"%s",
                   "currency_code":"INR","gross_amount_minor":100,"fee_amount_minor":0,
                   "adjustment_amount_minor":0,"net_amount_minor":100,
                   "validation_status":"VALID","validation_errors":[]},
                  {"row_number":3,"raw_row_text":"duplicate-reference-2","raw_row":{},
                   "external_transaction_id":"EXT-DUP-2","transaction_reference":"%s",
                   "currency_code":"INR","gross_amount_minor":100,"fee_amount_minor":0,
                   "adjustment_amount_minor":0,"net_amount_minor":100,
                   "validation_status":"VALID","validation_errors":[]}
                ]
                """.formatted(reference, reference);

        UUID batch = importSettlement(
                "SRC-" + UUID.randomUUID(), "RPT-" + UUID.randomUUID(),
                "3".repeat(64), rows);
        UUID run = jdbcTemplate.queryForObject(
                "SELECT public.reconcile_settlement_batch(?, ?)",
                UUID.class, batch, "rules-duplicate-reference-v1");

        assertThat(countOutcome(run, "DUPLICATE_EXTERNAL")).isEqualTo(2L);
        assertThat(countOutcome(run, "MATCHED")).isZero();
    }


@Test

    void runtimeCannotDirectlyInsertSettlementRows() {

        String[] executorInsertTables = {
                "public.settlement_batches",
                "public.settlement_records",
                "public.reconciliation_runs",
                "public.reconciliation_results"
        };
        for (String table : executorInsertTables) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT pg_catalog.has_table_privilege('ledger_executor', ?, 'INSERT')",
                    Boolean.class, table)).isTrue();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pg_catalog.has_table_privilege(" +
                        "'ledger_executor', 'public.reconciliation_resolution_audits', 'INSERT')",
                Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pg_catalog.has_table_privilege(" +
                        "'ledger_executor', 'public.reconciliation_resolution_audits', 'SELECT')",
                Boolean.class)).isTrue();

        assertThat(jdbcTemplate.queryForObject(

                "SELECT pg_catalog.has_table_privilege("

                        + "current_user, 'public.settlement_batches', 'INSERT')",

                Boolean.class)).isFalse();


        assertThat(jdbcTemplate.queryForObject(

                "SELECT pg_catalog.has_table_privilege("

                        + "current_user, 'public.settlement_records', 'INSERT')",

                Boolean.class)).isFalse();


                        for (String table : new String[] {
                "public.reconciliation_runs",
                "public.reconciliation_results",
                "public.reconciliation_resolution_audits"
        }) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT pg_catalog.has_table_privilege(current_user, ?, 'INSERT')",
                    Boolean.class, table)).isFalse();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT pg_catalog.has_function_privilege("
                        + "current_user, "
                        + "'public.reconcile_settlement_batch(uuid, character varying)', "
                        + "'EXECUTE')",
                Boolean.class)).isTrue();

        String rows = "[{\"row_number\":2,\"raw_row_text\":\"invalid\",\"raw_row\":{},"
                + "\"external_transaction_id\":\"EXT-AUDIT\","
                + "\"transaction_reference\":\"TX-AUDIT-" + UUID.randomUUID() + "\","
                + "\"currency_code\":\"INR\",\"gross_amount_minor\":-1,"
                + "\"fee_amount_minor\":0,\"adjustment_amount_minor\":0,"
                + "\"net_amount_minor\":-1,\"validation_status\":\"INVALID\","
                + "\"validation_errors\":[\"NEGATIVE_GROSS_AMOUNT\"]}]";
        UUID auditTestBatch = importSettlement(
                "SRC-AUDIT-" + UUID.randomUUID(), "RPT-AUDIT-" + UUID.randomUUID(),
                "8".repeat(64), rows);
        UUID auditTestRun = jdbcTemplate.queryForObject(
                "SELECT public.reconcile_settlement_batch(?, ?)", UUID.class,
                auditTestBatch, "audit-privilege-test-v1");
        UUID auditTestResult = jdbcTemplate.queryForObject(
                "SELECT id FROM public.reconciliation_results WHERE run_id = ?",
                UUID.class, auditTestRun);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO public.reconciliation_resolution_audits "
                        + "(result_id, action, actor_reference, reason) "
                        + "VALUES (?, 'ANNOTATE', 'test', 'unauthorized')",
                auditTestResult))
                .isInstanceOf(DataAccessException.class);


        String[] tables = {

                "public.settlement_batches",

                "public.settlement_records",

                "public.reconciliation_runs",

                "public.reconciliation_results",

                "public.reconciliation_resolution_audits"

        };


        for (String table : tables) {

            assertThat(jdbcTemplate.queryForObject(

                    "SELECT pg_catalog.has_table_privilege(current_user, ?, 'UPDATE')",

                    Boolean.class, table)).isFalse();


            assertThat(jdbcTemplate.queryForObject(

                    "SELECT pg_catalog.has_table_privilege(current_user, ?, 'DELETE')",

                    Boolean.class, table)).isFalse();

        }

        assertThat(jdbcTemplate.queryForObject(

                "SELECT pg_catalog.has_function_privilege("

                        + "current_user, "

                        + "'public.import_settlement_batch(character varying, character varying, "

                        + "character varying, text, timestamp with time zone, "

                        + "timestamp with time zone, jsonb)', 'EXECUTE')",

                Boolean.class)).isTrue();

    }

    @Test
    void runtimeCannotCreateSettlementObjectsOrMutateRecords() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pg_catalog.has_schema_privilege(current_user, 'public', 'CREATE')",
                Boolean.class)).isFalse();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO public.settlement_batches "
                        + "(source_code, report_id, file_sha256, original_filename, "
                        + "reporting_period_start, reporting_period_end) "
                        + "VALUES ('DIRECT', ?, ?, 'direct.csv', CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP + INTERVAL '1 day')",
                "DIRECT-" + UUID.randomUUID(), "a".repeat(64)))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE public.settlement_batches SET status = 'IMPORTED' WHERE id = ?",
                UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM public.reconciliation_runs WHERE id = ?",
                UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void importFunctionRejectsOversizedMetadataAndRollsBack() {
        String source = "SRC-" + UUID.randomUUID();
        String rows = "[{\"row_number\":2,\"raw_row_text\":\"x\",\"raw_row\":{},"
                + "\"external_transaction_id\":\"EXT\",\"transaction_reference\":\"TX\","
                + "\"currency_code\":\"INR\",\"gross_amount_minor\":100,"
                + "\"fee_amount_minor\":0,\"adjustment_amount_minor\":0,"
                + "\"net_amount_minor\":100,\"validation_status\":\"VALID\","
                + "\"validation_errors\":[]}]";

        assertThatThrownBy(() -> importSettlement(
                "S".repeat(81), "RPT", "d".repeat(64), rows))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> importSettlement(
                source, "R".repeat(151), "c".repeat(64), rows))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.settlement_batches WHERE source_code = ?",
                Long.class, source)).isZero();

        String malformedSource = "SRC-ROLLBACK-" + UUID.randomUUID();
        String validThenMalformedRows = rows.substring(0, rows.length() - 1) + ",[]]";
        assertThatThrownBy(() -> importSettlement(
                malformedSource, "RPT-ROLLBACK", "e".repeat(64), validThenMalformedRows))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.settlement_batches WHERE source_code = ?",
                Long.class, malformedSource)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.settlement_records "
                        + "WHERE batch_id IN (SELECT id FROM public.settlement_batches "
                        + "WHERE source_code = ?)", Long.class, malformedSource)).isZero();
    }


    private long countOutcome(UUID runId, String outcome) {

        return jdbcTemplate.queryForObject(

                "SELECT COUNT(*) FROM public.reconciliation_results "

                        + "WHERE run_id = ? AND outcome = ?",

                Long.class, runId, outcome);

    }


    @Test

    void settlementHttpRejectsInvalidHeaders() throws Exception {

        var file = new org.springframework.mock.web.MockMultipartFile(

                "file", "report.csv", "text/csv",

                "wrong,headers\nvalue,value\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));


        mockMvc.perform(

                org.springframework.test.web.servlet.request.MockMvcRequestBuilders

                        .multipart("/api/v1/settlement-batches")

                        .file(file)

                        .param("sourceCode", "HTTP-TEST")

                        .param("reportId", UUID.randomUUID().toString())

                        .param("reportingPeriodStart", "2026-01-01T00:00:00Z")

                        .param("reportingPeriodEnd", "2026-01-02T00:00:00Z"))

                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HEADERS"));

    }


    @Test

    void settlementHttpRejectsMalformedCsvQuoting() throws Exception {

        String csv = "external_transaction_id,transaction_reference,currency_code,"

                + "gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"

                + "\"unclosed,TX-1,INR,100,0,0,100\n";


        var file = new org.springframework.mock.web.MockMultipartFile(

                "file", "malformed.csv", "text/csv",

                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));


        mockMvc.perform(

                org.springframework.test.web.servlet.request.MockMvcRequestBuilders

                        .multipart("/api/v1/settlement-batches")

                        .file(file)

                        .param("sourceCode", "HTTP-TEST")

                        .param("reportId", UUID.randomUUID().toString())

                        .param("reportingPeriodStart", "2026-01-01T00:00:00Z")

                        .param("reportingPeriodEnd", "2026-01-02T00:00:00Z"))

                .andExpect(status().isBadRequest());

    }


    @Test

    void settlementHttpRejectsInvalidUtf8() throws Exception {

        byte[] header = (

                "external_transaction_id,transaction_reference,currency_code,"

                        + "gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"

        ).getBytes(java.nio.charset.StandardCharsets.UTF_8);


        byte[] bytes = java.util.Arrays.copyOf(header, header.length + 2);

        bytes[header.length] = (byte) 0xC3;

        bytes[header.length + 1] = (byte) '\n';


        var file = new org.springframework.mock.web.MockMultipartFile(

                "file", "invalid-utf8.csv", "text/csv", bytes);


        mockMvc.perform(

                org.springframework.test.web.servlet.request.MockMvcRequestBuilders

                        .multipart("/api/v1/settlement-batches")

                        .file(file)

                        .param("sourceCode", "HTTP-TEST")

                        .param("reportId", UUID.randomUUID().toString())

                        .param("reportingPeriodStart", "2026-01-01T00:00:00Z")

                        .param("reportingPeriodEnd", "2026-01-02T00:00:00Z"))

                .andExpect(status().isBadRequest())

                .andExpect(jsonPath("$.code").value("INVALID_UTF8"));

    }


    @Test

    void settlementHttpRejectsOversizedUpload() throws Exception {

        byte[] bytes = new byte[5 * 1024 * 1024 + 1];


        var file = new org.springframework.mock.web.MockMultipartFile(

                "file", "oversized.csv", "text/csv", bytes);


        mockMvc.perform(

                org.springframework.test.web.servlet.request.MockMvcRequestBuilders

                        .multipart("/api/v1/settlement-batches")

                        .file(file)

                        .param("sourceCode", "HTTP-TEST")

                        .param("reportId", UUID.randomUUID().toString())

                        .param("reportingPeriodStart", "2026-01-01T00:00:00Z")

                        .param("reportingPeriodEnd", "2026-01-02T00:00:00Z"))

                .andExpect(status().isPayloadTooLarge())

                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));

    }


    @Test

    void settlementHttpRejectsInvalidReportingPeriod() throws Exception {

        String csv = "external_transaction_id,transaction_reference,currency_code,"

                + "gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"

                + "EXT-1,TX-1,INR,100,0,0,100\n";


        var file = new org.springframework.mock.web.MockMultipartFile(

                "file", "report.csv", "text/csv",

                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));


        mockMvc.perform(

                org.springframework.test.web.servlet.request.MockMvcRequestBuilders

                        .multipart("/api/v1/settlement-batches")

                        .file(file)

                        .param("sourceCode", "HTTP-TEST")

                        .param("reportId", UUID.randomUUID().toString())

                        .param("reportingPeriodStart", "2026-01-02T00:00:00Z")

                        .param("reportingPeriodEnd", "2026-01-01T00:00:00Z"))

                .andExpect(status().isBadRequest())

                .andExpect(jsonPath("$.code").value("INVALID_REPORTING_PERIOD"));

    }


    @Test
    void settlementHttpRejectsBlankSourceCode() throws Exception {
        uploadValidSettlementCsv("", UUID.randomUUID().toString(),
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_SOURCE_CODE"));
    }

    @Test
    void settlementHttpRejectsOversizedSourceCode() throws Exception {
        uploadValidSettlementCsv("S".repeat(81), UUID.randomUUID().toString(),
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_SOURCE_CODE"));
    }

    @Test
    void settlementHttpRejectsBlankReportId() throws Exception {
        uploadValidSettlementCsv("HTTP-TEST", "",
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_REPORT_ID"));
    }

    @Test
    void settlementHttpRejectsMalformedReportingPeriodTimestamp() throws Exception {
        uploadValidSettlementCsv("HTTP-TEST", UUID.randomUUID().toString(),
                "not-a-timestamp", "2026-01-02T00:00:00Z")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    private org.springframework.test.web.servlet.ResultActions uploadValidSettlementCsv(
            String sourceCode, String reportId, String periodStart, String periodEnd)
            throws Exception {
        String csv = "external_transaction_id,transaction_reference,currency_code,"
                + "gross_amount_minor,fee_amount_minor,adjustment_amount_minor,net_amount_minor\n"
                + "EXT-1,TX-1,INR,100,0,0,100\n";

        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", "report.csv", "text/csv",
                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        return mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/settlement-batches")
                        .file(file)
                        .param("sourceCode", sourceCode)
                        .param("reportId", reportId)
                        .param("reportingPeriodStart", periodStart)
                        .param("reportingPeriodEnd", periodEnd));
    }

    @Test
    void transactionReadApiPagesAndPreservesLargeMinorUnitValues() throws Exception {
        String reference = "TX-READ-" + UUID.randomUUID();
        String largeAmount = "9007199254740993";
        String entries = """
                [
                  {"account_id":"11111111-1111-4111-8111-111111111111","direction":"DEBIT","amount_minor":%s},
                  {"account_id":"22222222-2222-4222-8222-222222222222","direction":"CREDIT","amount_minor":%s}
                ]
                """.formatted(largeAmount, largeAmount);
        UUID transactionId = post(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                reference, entries, null);
        long transactionsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.journal_transactions", Long.class);
        long entriesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.journal_entries", Long.class);

        String listBody = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/transactions")
                                .param("transactionReference", reference)
                                .param("currencyCode", "INR")
                                .param("page", "0")
                                .param("size", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = objectMapper.readTree(listBody);
        assertThat(list.get("totalElements").asLong()).isEqualTo(1L);
        assertThat(list.get("items").get(0).get("transactionId").asText())
                .isEqualTo(transactionId.toString());
        assertThat(list.get("items").get(0).get("totalDebitMinor").asText())
                .isEqualTo(largeAmount);

        String detailBody = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/transactions/{id}", transactionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode detail = objectMapper.readTree(detailBody);
        assertThat(detail.get("entries").size()).isEqualTo(2);
        assertThat(detail.get("entries").get(0).get("amountMinor").asText())
                .isEqualTo(largeAmount);
        assertThat(detail.get("entries").get(1).get("amountMinor").asText())
                .isEqualTo(largeAmount);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/transactions").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/transactions").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/transactions").param("currencyCode", "inr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENCY_CODE"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/transactions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.journal_transactions", Long.class))
                .isEqualTo(transactionsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.journal_entries", Long.class))
                .isEqualTo(entriesBefore);
    }

    @Test
    void settlementReadApiReturnsPersistedRowsRunsAndResultsWithoutMutation() throws Exception {
        String reference = "TX-SETTLEMENT-READ-" + UUID.randomUUID();
        post(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                reference, balancedEntries(), null);
        String source = "SRC-READ-" + UUID.randomUUID();
        String report = "RPT-READ-" + UUID.randomUUID();
        String rows = """
                [
                  {"row_number":2,"raw_row_text":"valid","raw_row":{"external_transaction_id":"EXT-READ"},
                   "external_transaction_id":"EXT-READ","transaction_reference":"%s",
                   "currency_code":"INR","gross_amount_minor":100,"fee_amount_minor":0,
                   "adjustment_amount_minor":0,"net_amount_minor":100,
                   "validation_status":"VALID","validation_errors":[]},
                  {"row_number":3,"raw_row_text":"invalid","raw_row":{"bad":true},
                   "external_transaction_id":null,"transaction_reference":null,
                   "currency_code":null,"gross_amount_minor":-1,"fee_amount_minor":0,
                   "adjustment_amount_minor":0,"net_amount_minor":-1,
                   "validation_status":"INVALID","validation_errors":["NEGATIVE_GROSS_AMOUNT"]}
                ]
                """.formatted(reference);
        UUID batchId = importSettlement(source, report, "a".repeat(64), rows);
        UUID runId = jdbcTemplate.queryForObject(
                "SELECT public.reconcile_settlement_batch(?, ?)", UUID.class,
                batchId, "read-api-v1");
        long entriesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.journal_entries", Long.class);
        long resultsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.reconciliation_results WHERE run_id = ?",
                Long.class, runId);

        String batchBody = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/settlement-batches")
                                .param("sourceCode", source).param("reportId", report))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode batches = objectMapper.readTree(batchBody);
        assertThat(batches.get("totalElements").asLong()).isEqualTo(1L);
        JsonNode batch = batches.get("items").get(0);
        assertThat(batch.get("batchId").asText()).isEqualTo(batchId.toString());
        assertThat(batch.get("recordCount").asLong()).isEqualTo(2L);
        assertThat(batch.get("validRecordCount").asLong()).isEqualTo(1L);
        assertThat(batch.get("invalidRecordCount").asLong()).isEqualTo(1L);

        String invalidRowsBody = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/settlement-batches/{id}/records", batchId)
                                .param("validationStatus", "INVALID"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode invalidRows = objectMapper.readTree(invalidRowsBody);
        assertThat(invalidRows.get("totalElements").asLong()).isEqualTo(1L);
        assertThat(invalidRows.get("items").get(0).get("validationErrors").get(0).asText())
                .isEqualTo("NEGATIVE_GROSS_AMOUNT");

        String runsBody = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/settlement-batches/{id}/reconciliation-runs", batchId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(runsBody).get("items").get(0).get("runId").asText())
                .isEqualTo(runId.toString());

        String resultsBody = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/reconciliation-runs/{id}/results", runId)
                                .param("outcome", "MATCHED"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode results = objectMapper.readTree(resultsBody);
        assertThat(results.get("totalElements").asLong()).isEqualTo(1L);
        assertThat(results.get("items").get(0).get("reportedGrossMinor").asText())
                .isEqualTo("100");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/settlement-batches/{id}/records", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_BATCH_NOT_FOUND"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/reconciliation-runs/{id}/results", runId)
                        .param("outcome", "NOT_REAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECONCILIATION_OUTCOME"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.journal_entries", Long.class))
                .isEqualTo(entriesBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.reconciliation_results WHERE run_id = ?",
                Long.class, runId)).isEqualTo(resultsBefore);
    }
}
