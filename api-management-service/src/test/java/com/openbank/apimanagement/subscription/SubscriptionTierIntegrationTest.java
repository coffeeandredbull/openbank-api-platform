package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.exception.SubscriptionTierAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class SubscriptionTierIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SubscriptionTierService subscriptionTierService;

    @Autowired
    private SubscriptionTierRepository subscriptionTierRepository;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM subscriptions");
        jdbcTemplate.execute("DELETE FROM subscription_tiers");
    }

    @Test
    void serviceCreatesPersistsAndReadsBackATier() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);

        assertThat(created.getId()).isNotNull();

        SubscriptionTier stored = subscriptionTierRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Gold");
        assertThat(stored.getDescription()).isEqualTo("Premium access");
        assertThat(stored.getRequestsPerWindow()).isEqualTo(1000);
        assertThat(stored.getWindowSeconds()).isEqualTo(60);
        assertThat(stored.getCreatedAt()).isEqualTo(stored.getUpdatedAt());
    }

    @Test
    void serviceRejectsDuplicateTierName() {
        subscriptionTierService.create("Gold", "Premium access", 1000, 60);

        assertThatThrownBy(() -> subscriptionTierService.create("Gold", "Another description", 100, 60))
                .isInstanceOf(SubscriptionTierAlreadyExistsException.class)
                .hasMessageContaining("Gold");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from subscription_tiers where name = 'Gold'", Long.class)).isEqualTo(1L);
    }

    @Test
    void serviceRejectsInvalidNamesWithoutPersisting() {
        assertThatThrownBy(() -> subscriptionTierService.create("", "x", 100, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
        assertThatThrownBy(() -> subscriptionTierService.create(" Gold ", "x", 100, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
        assertThatThrownBy(() -> subscriptionTierService.create("a".repeat(101), "x", 100, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
        assertThatThrownBy(() -> subscriptionTierService.create("Gold", "a".repeat(501), 100, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
        assertThatThrownBy(() -> subscriptionTierService.create("Gold", "x", 0, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestsPerWindow");
        assertThatThrownBy(() -> subscriptionTierService.create("Gold", "x", 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSeconds");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from subscription_tiers", Long.class)).isZero();
    }

    @Test
    void duplicateTierNamesAreRejectedByTheDatabaseUniqueConstraint() {
        jdbcTemplate.update(
                "INSERT INTO subscription_tiers "
                        + "(name, description, requests_per_window, window_seconds, created_at, updated_at) "
                        + "VALUES ('Gold', 'Premium', 1000, 60, now(), now())");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO subscription_tiers "
                        + "(name, description, requests_per_window, window_seconds, created_at, updated_at) "
                        + "VALUES ('Gold', 'Another', 100, 60, now(), now())"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from subscription_tiers", Long.class)).isEqualTo(1L);
    }

    @Test
    void subscriptionTiersTableHasExpectedSchemaAndConstraints() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscription_tiers' "
                        + "order by ordinal_position",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "name", "description", "requests_per_window", "window_seconds", "created_at", "updated_at");

        List<String> nullableColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscription_tiers' "
                        + "and is_nullable = 'YES'",
                String.class);
        assertThat(nullableColumns).containsExactlyInAnyOrder("description");

        Integer nameLength = jdbcTemplate.queryForObject(
                "select character_maximum_length from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscription_tiers' "
                        + "and column_name = 'name'",
                Integer.class);
        assertThat(nameLength).isEqualTo(100);

        Integer descriptionLength = jdbcTemplate.queryForObject(
                "select character_maximum_length from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscription_tiers' "
                        + "and column_name = 'description'",
                Integer.class);
        assertThat(descriptionLength).isEqualTo(500);

        String defaultRequestsPerWindow = jdbcTemplate.queryForObject(
                "select column_default from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscription_tiers' "
                        + "and column_name = 'requests_per_window'",
                String.class);
        assertThat(defaultRequestsPerWindow).isEqualTo("100");

        String defaultWindowSeconds = jdbcTemplate.queryForObject(
                "select column_default from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscription_tiers' "
                        + "and column_name = 'window_seconds'",
                String.class);
        assertThat(defaultWindowSeconds).isEqualTo("60");

        List<String> uniqueColumns = jdbcTemplate.queryForList(
                "select a.attname from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "join pg_attribute a on a.attrelid = con.conrelid and a.attnum = any(con.conkey) "
                        + "where rel.relname = 'subscription_tiers' and con.contype = 'u' "
                        + "order by a.attnum",
                String.class);
        assertThat(uniqueColumns).containsExactlyInAnyOrder("name");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "where rel.relname = 'subscription_tiers' and con.contype = 'f'",
                Integer.class)).isZero();
    }
}