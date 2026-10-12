package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.api.Api;
import com.openbank.apimanagement.api.ApiRepository;
import com.openbank.apimanagement.api.ApiVersion;
import com.openbank.apimanagement.api.ApiVersionRepository;
import com.openbank.apimanagement.application.Application;
import com.openbank.apimanagement.application.ApplicationRepository;
import com.openbank.apimanagement.exception.SubscriptionTierAlreadyExistsException;
import com.openbank.apimanagement.exception.SubscriptionTierInUseException;
import com.openbank.apimanagement.exception.SubscriptionTierNotFoundException;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
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
    private MockMvc mockMvc;

    @Autowired
    private SubscriptionTierService subscriptionTierService;

    @Autowired
    private SubscriptionTierRepository subscriptionTierRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private ApiRepository apiRepository;

    @Autowired
    private ApiVersionRepository apiVersionRepository;

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
        assertThat(stored.getPriceCentsPerMonth()).isZero();
        assertThat(stored.getCreatedAt()).isEqualTo(stored.getUpdatedAt());
    }

    @Test
    void createEndpointPersistsTierWithDefaultsAndReturnsSafeBody() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Bronze",
                                  "description": "Basic access"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/subscription-tiers/")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Bronze"))
                .andExpect(jsonPath("$.description").value("Basic access"))
                .andExpect(jsonPath("$.requestsPerWindow").value(100))
                .andExpect(jsonPath("$.windowSeconds").value(60))
                .andExpect(jsonPath("$.priceCentsPerMonth").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(jdbcTemplate.queryForObject(
                "select requests_per_window from subscription_tiers where name = 'Bronze'", Integer.class))
                .isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject(
                "select price_cents_per_month from subscription_tiers where name = 'Bronze'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select status from subscription_tiers where name = 'Bronze'", String.class))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select window_seconds from subscription_tiers where name = 'Bronze'", Integer.class))
                .isEqualTo(60);
    }

    @Test
    void createEndpointPersistsExplicitRateLimitPolicy() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Gold",
                                  "description": "Premium access",
                                  "requestsPerWindow": 1000,
                                  "windowSeconds": 30
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestsPerWindow").value(1000))
                .andExpect(jsonPath("$.windowSeconds").value(30));

        assertThat(jdbcTemplate.queryForObject(
                "select requests_per_window from subscription_tiers where name = 'Gold'", Integer.class))
                .isEqualTo(1000);
        assertThat(jdbcTemplate.queryForObject(
                "select window_seconds from subscription_tiers where name = 'Gold'", Integer.class))
                .isEqualTo(30);
    }

    @Test
    void createEndpointPersistsExplicitPrice() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Platinum",
                                  "description": "Premium access",
                                  "requestsPerWindow": 5000,
                                  "windowSeconds": 60,
                                  "priceCentsPerMonth": 4999
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceCentsPerMonth").value(4999));

        assertThat(jdbcTemplate.queryForObject(
                "select price_cents_per_month from subscription_tiers where name = 'Platinum'", Integer.class))
                .isEqualTo(4999);
    }

    @Test
    void createEndpointPersistsAllowedScopesAndReturnsTheCanonicalValue() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Scoped",
                                  "description": "Scoped access",
                                  "allowedScopes": ["payments:read", "orders:write"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowedScopes").value("payments:read orders:write"));

        assertThat(jdbcTemplate.queryForObject(
                "select allowed_scopes from subscription_tiers where name = 'Scoped'", String.class))
                .isEqualTo("payments:read orders:write");
    }

    @Test
    void createEndpointWithoutAllowedScopesStoresTheUnrestrictedEmptyString() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Unscoped",
                                  "description": "Unrestricted access"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowedScopes").value(""));

        assertThat(jdbcTemplate.queryForObject(
                "select allowed_scopes from subscription_tiers where name = 'Unscoped'", String.class))
                .isEmpty();
    }

    @Test
    void createEndpointRejectsInvalidAllowedScopeTokensWith400() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Invalid",
                                  "allowedScopes": ["INVALID SCOPE"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.allowedScopes").value(
                        "scopes must be unique tokens of at most 64 characters like 'accounts:read'"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from subscription_tiers", Long.class)).isZero();
    }

    @Test
    void createEndpointRejectsNegativePriceWith400() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Invalid",
                                  "description": "Negative price",
                                  "priceCentsPerMonth": -1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.priceCentsPerMonth").value(
                        "priceCentsPerMonth must not be negative"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from subscription_tiers", Long.class)).isZero();
    }

    @Test
    void createEndpointRejectsDuplicateNameWith409() throws Exception {
        subscriptionTierService.create("Gold", "First", 100, 60);

        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Gold",
                                  "description": "Second"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value(
                        "A subscription tier with name 'Gold' already exists"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from subscription_tiers where name = 'Gold'", Long.class)).isEqualTo(1L);
    }

    @Test
    void createEndpointRejectsMissingNameWith400() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "No name"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").value("name is required"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from subscription_tiers", Long.class)).isZero();
    }

    @Test
    void listEndpointReturnsTiersOrderedByIdAsc() throws Exception {
        subscriptionTierService.create("Silver", "Second", 200, 60);
        subscriptionTierService.create("Gold", "First", 1000, 60);

        mockMvc.perform(get("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Silver"))
                .andExpect(jsonPath("$[0].requestsPerWindow").value(200))
                .andExpect(jsonPath("$[0].priceCentsPerMonth").value(0))
                .andExpect(jsonPath("$[1].name").value("Gold"))
                .andExpect(jsonPath("$[1].requestsPerWindow").value(1000))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void updateEndpointAppliesPriceOnlyPatchAndPersistsIt() throws Exception {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);

        mockMvc.perform(patch("/subscription-tiers/" + created.getId())
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "priceCentsPerMonth": 2999
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Gold"))
                .andExpect(jsonPath("$.requestsPerWindow").value(1000))
                .andExpect(jsonPath("$.priceCentsPerMonth").value(2999));

        assertThat(jdbcTemplate.queryForObject(
                "select price_cents_per_month from subscription_tiers where id = ?", Integer.class, created.getId()))
                .isEqualTo(2999);
        SubscriptionTier rehydrated = subscriptionTierService.get(created.getId());
        assertThat(rehydrated.getPriceCentsPerMonth()).isEqualTo(2999);
    }

    @Test
    void updateEndpointRejectsNegativePriceWith400() throws Exception {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);

        mockMvc.perform(patch("/subscription-tiers/" + created.getId())
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "priceCentsPerMonth": -100
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.priceCentsPerMonth").value(
                        "priceCentsPerMonth must not be negative"));

        assertThat(jdbcTemplate.queryForObject(
                "select price_cents_per_month from subscription_tiers where id = ?", Integer.class, created.getId()))
                .isZero();
    }

    @Test
    void updateEndpointAppliesAllowedScopesOnlyPatchAndPersistsIt() throws Exception {
        SubscriptionTier created = subscriptionTierService.create(
                "Scoped Patch", "Premium access", 1000, 60, 0, "orders:read");

        mockMvc.perform(patch("/subscription-tiers/" + created.getId())
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "allowedScopes": ["payments:read", "payments:write"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Scoped Patch"))
                .andExpect(jsonPath("$.requestsPerWindow").value(1000))
                .andExpect(jsonPath("$.allowedScopes").value("payments:read payments:write"));

        assertThat(jdbcTemplate.queryForObject(
                "select allowed_scopes from subscription_tiers where id = ?", String.class, created.getId()))
                .isEqualTo("payments:read payments:write");
        SubscriptionTier rehydrated = subscriptionTierService.get(created.getId());
        assertThat(rehydrated.getAllowedScopes()).isEqualTo("payments:read payments:write");
    }

    @Test
    void updateEndpointRejectsInvalidAllowedScopeTokensWith400() throws Exception {
        SubscriptionTier created = subscriptionTierService.create(
                "Scoped Invalid", "Premium access", 1000, 60, 0, "payments:read");

        mockMvc.perform(patch("/subscription-tiers/" + created.getId())
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "allowedScopes": ["INVALID SCOPE"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.allowedScopes").value(
                        "scopes must be unique tokens of at most 64 characters like 'accounts:read'"));

        assertThat(jdbcTemplate.queryForObject(
                "select allowed_scopes from subscription_tiers where id = ?", String.class, created.getId()))
                .isEqualTo("payments:read");
    }

    @Test
    void updateEndpointRejectsUnknownTierWith404() throws Exception {
        mockMvc.perform(patch("/subscription-tiers/999")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "priceCentsPerMonth": 100
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_NOT_FOUND"));
    }

    @Test
    void tiersInsertedWithoutThePriceColumnDefaultToZero() {
        jdbcTemplate.update(
                "INSERT INTO subscription_tiers "
                        + "(name, description, requests_per_window, window_seconds, created_at, updated_at) "
                        + "VALUES ('Legacy', 'Existing row', 100, 60, now(), now())");

        assertThat(jdbcTemplate.queryForObject(
                "select price_cents_per_month from subscription_tiers where name = 'Legacy'", Integer.class))
                .isZero();
    }

    @Test
    void tiersInsertedWithoutTheAllowedScopesColumnDefaultToEmptyString() {
        jdbcTemplate.update(
                "INSERT INTO subscription_tiers "
                        + "(name, description, requests_per_window, window_seconds, created_at, updated_at) "
                        + "VALUES ('Legacy Scopes', 'Existing row', 100, 60, now(), now())");

        assertThat(jdbcTemplate.queryForObject(
                "select allowed_scopes from subscription_tiers where name = 'Legacy Scopes'", String.class))
                .isEmpty();
    }

    @Test
    void listEndpointReturnsEmptyArrayWhenNoTiersExist() throws Exception {
        mockMvc.perform(get("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void serviceUpdatesMutableFieldsAndPersistsThem() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);
        Instant createdAt = subscriptionTierRepository.findById(created.getId())
                .orElseThrow()
                .getCreatedAt();

        SubscriptionTierResponse response = subscriptionTierService.update(
                created.getId(),
                new UpdateSubscriptionTierRequest("Premium", "Updated access", 500, 30, null));

        assertThat(response.id()).isEqualTo(created.getId());
        assertThat(response.name()).isEqualTo("Premium");
        assertThat(response.description()).isEqualTo("Updated access");
        assertThat(response.requestsPerWindow()).isEqualTo(500);
        assertThat(response.windowSeconds()).isEqualTo(30);
        assertThat(response.createdAt()).isEqualTo(createdAt);

        SubscriptionTier stored = subscriptionTierRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Premium");
        assertThat(stored.getDescription()).isEqualTo("Updated access");
        assertThat(stored.getRequestsPerWindow()).isEqualTo(500);
        assertThat(stored.getWindowSeconds()).isEqualTo(30);
        assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
        assertThat(stored.getUpdatedAt()).isAfter(createdAt);
    }

    @Test
    void serviceUpdatesOnlySuppliedFields() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Keep this", 1000, 60);

        SubscriptionTierResponse response = subscriptionTierService.update(
                created.getId(), new UpdateSubscriptionTierRequest("Premium", null, null, null, null));

        assertThat(response.name()).isEqualTo("Premium");
        assertThat(response.description()).isEqualTo("Keep this");
        assertThat(response.requestsPerWindow()).isEqualTo(1000);
        assertThat(response.windowSeconds()).isEqualTo(60);
    }

    @Test
    void serviceAllowsUpdatingATierToItsExistingName() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);

        SubscriptionTierResponse response = subscriptionTierService.update(
                created.getId(), new UpdateSubscriptionTierRequest("Gold", "Updated access", 500, 30, null));

        assertThat(response.name()).isEqualTo("Gold");
        assertThat(response.description()).isEqualTo("Updated access");
    }

    @Test
    void serviceRejectsDuplicateNameOnUpdateWithoutChangingRows() {
        SubscriptionTier first = subscriptionTierService.create("Gold", "First", 1000, 60);
        SubscriptionTier second = subscriptionTierService.create("Silver", "Second", 2000, 30);

        assertThatThrownBy(() -> subscriptionTierService.update(
                first.getId(), new UpdateSubscriptionTierRequest("Silver", "Changed", 1, 1, null)))
                .isInstanceOf(SubscriptionTierAlreadyExistsException.class)
                .hasMessageContaining("Silver");

        SubscriptionTier unchangedFirst = subscriptionTierRepository.findById(first.getId()).orElseThrow();
        SubscriptionTier unchangedSecond = subscriptionTierRepository.findById(second.getId()).orElseThrow();
        assertThat(unchangedFirst.getName()).isEqualTo("Gold");
        assertThat(unchangedFirst.getDescription()).isEqualTo("First");
        assertThat(unchangedFirst.getRequestsPerWindow()).isEqualTo(1000);
        assertThat(unchangedFirst.getWindowSeconds()).isEqualTo(60);
        assertThat(unchangedSecond.getName()).isEqualTo("Silver");
    }

    @Test
    void serviceDeletesUnreferencedTier() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);

        subscriptionTierService.delete(created.getId());

        assertThat(subscriptionTierRepository.findById(created.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from subscription_tiers where id = ?", Long.class, created.getId()))
                .isZero();
    }

    @Test
    void serviceRejectsReferencedTierWithoutDeletingSubscription() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);
        Subscription subscription = createSubscription(created);

        assertThatThrownBy(() -> subscriptionTierService.delete(created.getId()))
                .isInstanceOf(SubscriptionTierInUseException.class)
                .hasMessageContaining(String.valueOf(created.getId()));

        assertThat(subscriptionTierRepository.findById(created.getId())).isPresent();
        assertThat(subscriptionRepository.findById(subscription.getId())).isPresent();
        assertThat(jdbcTemplate.queryForObject("select count(*) from subscriptions", Long.class)).isEqualTo(1L);
    }

    @Test
    void serviceRejectsUnknownTierOnDelete() {
        assertThatThrownBy(() -> subscriptionTierService.delete(999L))
                .isInstanceOf(SubscriptionTierNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void repeatedDeleteReturnsNotFoundAfterFirstDelete() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);

        subscriptionTierService.delete(created.getId());

        assertThatThrownBy(() -> subscriptionTierService.delete(created.getId()))
                .isInstanceOf(SubscriptionTierNotFoundException.class);
        assertThat(subscriptionTierRepository.findById(created.getId())).isEmpty();
    }

    @Test
    void databaseForeignKeyRejectsDirectTierDeleteWithoutCascade() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);
        Subscription subscription = createSubscription(created);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from subscription_tiers where id = ?", created.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(subscriptionTierRepository.findById(created.getId())).isPresent();
        assertThat(subscriptionRepository.findById(subscription.getId())).isPresent();
    }

    @Test
    void serviceRejectsUnknownTierOnUpdate() {
        assertThatThrownBy(() -> subscriptionTierService.update(
                999L, new UpdateSubscriptionTierRequest("Gold", null, null, null, null)))
                .isInstanceOf(com.openbank.apimanagement.exception.SubscriptionTierNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void serviceRejectsEmptyUpdate() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);

        assertThatThrownBy(() -> subscriptionTierService.update(
                created.getId(), new UpdateSubscriptionTierRequest(null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one field");
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
    void updateStatusEndpointPersistsNewStatusAndReturnsIt() throws Exception {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);

        mockMvc.perform(patch("/subscription-tiers/" + created.getId() + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "INACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.getId().intValue()))
                .andExpect(jsonPath("$.name").value("Gold"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        assertThat(jdbcTemplate.queryForObject(
                "select status from subscription_tiers where id = ?", String.class, created.getId()))
                .isEqualTo("INACTIVE");
    }

    @Test
    void updateStatusEndpointReactivatesADeactivatedTier() throws Exception {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);
        subscriptionTierService.changeStatus(
                created.getId(), new UpdateSubscriptionTierStatusRequest(SubscriptionTierStatus.INACTIVE));

        mockMvc.perform(patch("/subscription-tiers/" + created.getId() + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(jdbcTemplate.queryForObject(
                "select status from subscription_tiers where id = ?", String.class, created.getId()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void updateStatusEndpointRejectsMissingStatusWith400() throws Exception {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);

        mockMvc.perform(patch("/subscription-tiers/" + created.getId() + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("status is required"));

        assertThat(jdbcTemplate.queryForObject(
                "select status from subscription_tiers where id = ?", String.class, created.getId()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void updateStatusEndpointRejectsUnknownTierWith404() throws Exception {
        mockMvc.perform(patch("/subscription-tiers/999/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "INACTIVE"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_NOT_FOUND"));
    }

    @Test
    void changeStatusWithTheSameStatusDoesNotTouchUpdatedAt() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);
        String updatedAtBefore = jdbcTemplate.queryForObject(
                "select updated_at::text from subscription_tiers where id = ?", String.class, created.getId());

        subscriptionTierService.changeStatus(
                created.getId(), new UpdateSubscriptionTierStatusRequest(SubscriptionTierStatus.ACTIVE));

        String updatedAtAfter = jdbcTemplate.queryForObject(
                "select updated_at::text from subscription_tiers where id = ?", String.class, created.getId());
        assertThat(updatedAtAfter).isEqualTo(updatedAtBefore);
    }

    @Test
    void deactivatingATierKeepsExistingSubscriptionsIntact() {
        SubscriptionTier created = subscriptionTierService.create("Gold", "Premium access", 1000, 60);
        Subscription subscription = createSubscription(created);

        subscriptionTierService.changeStatus(
                created.getId(), new UpdateSubscriptionTierStatusRequest(SubscriptionTierStatus.INACTIVE));

        assertThat(jdbcTemplate.queryForObject(
                "select status from subscription_tiers where id = ?", String.class, created.getId()))
                .isEqualTo("INACTIVE");
        assertThat(subscriptionRepository.findById(subscription.getId())).isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "select tier_id from subscriptions where id = ?", Long.class, subscription.getId()))
                .isEqualTo(created.getId());
    }

    private Subscription createSubscription(SubscriptionTier created) {
        Api api = apiRepository.saveAndFlush(new Api(
                "Payments API " + System.nanoTime(), "Payment operations", "/payments-" + System.nanoTime()));
        ApiVersion version = apiVersionRepository.saveAndFlush(new ApiVersion(api, "v1"));
        Application application = applicationRepository.saveAndFlush(new Application(
                "Test App " + System.nanoTime(), "Integration test", 42L));
        SubscriptionTier managedTier = subscriptionTierRepository.findById(created.getId()).orElseThrow();
        return subscriptionRepository.saveAndFlush(new Subscription(application, version, managedTier));
    }

    private String token(String subject, String role, long expiresInSeconds) throws Exception {
        java.time.Instant now = java.time.Instant.now();
        com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .subject(subject)
                .claim("role", role)
                .issueTime(java.util.Date.from(now))
                .expirationTime(java.util.Date.from(now.plusSeconds(expiresInSeconds)))
                .build();
        com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.HS256).build(),
                claims);
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(
                "integration-test-jwt-secret-value-0123456789-ab"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Test
    void subscriptionTiersTableHasExpectedSchemaAndConstraints() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscription_tiers' "
                        + "order by ordinal_position",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "name", "description", "requests_per_window", "window_seconds", "price_cents_per_month",
                "allowed_scopes", "status", "created_at", "updated_at");

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

        String defaultPriceCentsPerMonth = jdbcTemplate.queryForObject(
                "select column_default from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscription_tiers' "
                        + "and column_name = 'price_cents_per_month'",
                String.class);
        assertThat(defaultPriceCentsPerMonth).isEqualTo("0");

        String defaultAllowedScopes = jdbcTemplate.queryForObject(
                "select column_default from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscription_tiers' "
                        + "and column_name = 'allowed_scopes'",
                String.class);
        assertThat(defaultAllowedScopes).isEqualTo("''::text");

        String defaultStatus = jdbcTemplate.queryForObject(
                "select column_default from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscription_tiers' "
                        + "and column_name = 'status'",
                String.class);
        assertThat(defaultStatus).isEqualTo("'ACTIVE'::character varying");

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