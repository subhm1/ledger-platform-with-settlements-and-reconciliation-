package com.ledger.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class LedgerPlatformApplicationTests {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18")
                    .withDatabaseName("ledger")
                    .withUsername("ledger_app")
                    .withPassword("local_dev_password")
                    .withCommand("postgres", "-c", "timezone=Asia/Kolkata");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> postgres.getJdbcUrl() + "?options=-c%20TimeZone=Asia/Kolkata"
        );
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void contextLoads() {
        assertThat(jdbcTemplate).isNotNull();
    }

    @Test
    void connectsToPostgresAndRunsFlyway() {
        String databaseName = jdbcTemplate.queryForObject(
                "SELECT current_database()", String.class
        );

        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history " +
                "WHERE version = '1' AND success = true",
                Integer.class
        );

        assertThat(databaseName).isEqualTo("ledger");
        assertThat(successfulMigrations).isEqualTo(1);
    }
}
