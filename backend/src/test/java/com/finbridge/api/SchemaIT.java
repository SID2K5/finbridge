package com.finbridge.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finbridge.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the Flyway migration really ran on MySQL and produced the designed schema.
 * (The application context also starts with ddl-auto=validate, so every entity mapping
 * has been checked against these tables before any test runs.)
 */
class SchemaIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void baselineMigrationWasAppliedSuccessfully() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1", Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void allTwelveTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);

        assertThat(tables).contains("roles", "users", "user_roles", "suppliers", "invoices",
                "payment_transactions", "integration_logs", "sync_runs", "synchronization_history",
                "reconciliation_runs", "reconciliation_records", "integration_configurations");
    }

    @Test
    void theThreeRolesAreSeededByTheMigration() {
        List<String> roles = jdbcTemplate.queryForList("SELECT name FROM roles ORDER BY name", String.class);

        assertThat(roles).containsExactly("ADMIN", "FINANCE_MANAGER", "INTEGRATION_OPERATOR");
    }

    @Test
    void moneyColumnsAreExactDecimalsNotFloatingPoint() {
        List<String> types = jdbcTemplate.queryForList(
                "SELECT DISTINCT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND column_name LIKE '%amount%'", String.class);

        assertThat(types).containsExactly("decimal(19,4)");
    }

    @Test
    void demoSeedDataIsNotLoadedOutsideTheDevProfile() {
        Integer demoRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM suppliers WHERE name LIKE '[DEMO]%'", Integer.class);

        assertThat(demoRows).isZero();
    }
}
