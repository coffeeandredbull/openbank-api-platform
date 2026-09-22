package com.openbank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "JWT_SECRET=" + GatewayTestJwt.SECRET)
class GatewayRouteConfigTest {

    private static final Set<String> REQUIRED_ROUTE_IDS = Set.of(
            "identity_users", "identity_auth",
            "api_management_apis", "api_management_applications",
            "api_management_subscriptions", "api_management_credentials",
            "payment_accounts", "payment_payments", "payment_transactions",
            "managed_api_invocation"
    );

    @Autowired
    GatewayProperties gatewayProperties;

    @Autowired
    HttpClientProperties httpClientProperties;

    @Autowired
    ServerProperties serverProperties;

    private Map<String, RouteDefinition> routes() {
        return gatewayProperties.getRoutes().stream()
                .collect(Collectors.toMap(RouteDefinition::getId, Function.identity()));
    }

    @Test
    void gatewayRunsOnPort8080() {
        assertThat(serverProperties.getPort()).isEqualTo(8080);
    }

    @Test
    void allRequiredRoutesAreRegistered() {
        assertThat(routes().keySet()).containsExactlyInAnyOrderElementsOf(REQUIRED_ROUTE_IDS);
    }

    @Test
    void defaultUpstreamUrlsFollowTheDocumentedDevelopmentProfile() {
        Map<String, RouteDefinition> byId = routes();
        assertThat(byId.get("identity_users").getUri().toString()).isEqualTo("http://localhost:8081");
        assertThat(byId.get("identity_auth").getUri().toString()).isEqualTo("http://localhost:8081");
        assertThat(byId.get("api_management_apis").getUri().toString()).isEqualTo("http://localhost:8081");
        assertThat(byId.get("api_management_applications").getUri().toString()).isEqualTo("http://localhost:8081");
        assertThat(byId.get("api_management_subscriptions").getUri().toString()).isEqualTo("http://localhost:8081");
        assertThat(byId.get("api_management_credentials").getUri().toString()).isEqualTo("http://localhost:8081");
        assertThat(byId.get("payment_accounts").getUri().toString()).isEqualTo("http://localhost:8082");
        assertThat(byId.get("payment_payments").getUri().toString()).isEqualTo("http://localhost:8082");
        assertThat(byId.get("payment_transactions").getUri().toString()).isEqualTo("http://localhost:8082");
        assertThat(byId.get("managed_api_invocation").getUri().toString())
                .isEqualTo("http://localhost:8084");
    }

    @Test
    void noRoutePointsBackToTheGatewayPort() {
        assertThat(routes().values())
                .allSatisfy(route -> assertThat(route.getUri().getPort()).isNotEqualTo(8080));
    }

    @Test
    void routePathsPreserveTheOriginalUriWithNoRewriting() {
        Map<String, String> expectedPaths = Map.of(
                "identity_users", "/users/**",
                "identity_auth", "/auth/**",
                "api_management_apis", "/apis/**",
                "api_management_applications", "/applications/**",
                "api_management_subscriptions", "/subscriptions/**",
                "api_management_credentials", "/credentials/**",
                "payment_accounts", "/accounts/**",
                "payment_payments", "/payments/**",
                "payment_transactions", "/transactions/**",
                "managed_api_invocation", "/runtime/apis/**"
        );
        Map<String, RouteDefinition> byId = routes();
        for (Map.Entry<String, String> entry : expectedPaths.entrySet()) {
            RouteDefinition route = byId.get(entry.getKey());
            assertThat(route.getPredicates()).anySatisfy(predicate -> {
                assertThat(predicate.getName()).isEqualTo("Path");
                assertThat(predicate.getArgs().values()).contains(entry.getValue());
            });
            assertThat(route.getFilters()).isEmpty();
        }
    }

    @Test
    void httpClientTimeoutsAreConfigured() {
        assertThat(httpClientProperties.getConnectTimeout()).isEqualTo(2000);
        assertThat(httpClientProperties.getResponseTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void noSecretMaterialIsHardcodedInGatewayConfiguration() throws IOException {
        String config;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).isNotNull();
            config = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(config.toLowerCase())
                .doesNotContain("secret")
                .doesNotContain("password")
                .doesNotContain("jwt")
                .doesNotContain("api-key")
                .doesNotContain("token=");
    }
}