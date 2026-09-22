package com.openbank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "IDENTITY_SERVICE_URL=http://identity.internal:9001",
        "API_MANAGEMENT_SERVICE_URL=http://api-management.internal:9002",
        "PAYMENT_SERVICE_URL=http://payment.internal:9003",
        "MANAGED_API_TARGET_URL=http://managed.internal:9004",
        "JWT_SECRET=" + GatewayTestJwt.SECRET
})
class GatewayRouteConfigEnvOverrideTest {

    @Autowired
    GatewayProperties gatewayProperties;

    private Map<String, RouteDefinition> routes() {
        return gatewayProperties.getRoutes().stream()
                .collect(Collectors.toMap(RouteDefinition::getId, Function.identity()));
    }

    @Test
    void environmentVariablesOverrideDefaultUpstreamUrls() {
        Map<String, RouteDefinition> byId = routes();
        assertThat(byId.get("identity_users").getUri().toString()).isEqualTo("http://identity.internal:9001");
        assertThat(byId.get("identity_auth").getUri().toString()).isEqualTo("http://identity.internal:9001");
        assertThat(byId.get("api_management_apis").getUri().toString()).isEqualTo("http://api-management.internal:9002");
        assertThat(byId.get("api_management_applications").getUri().toString()).isEqualTo("http://api-management.internal:9002");
        assertThat(byId.get("api_management_subscriptions").getUri().toString()).isEqualTo("http://api-management.internal:9002");
        assertThat(byId.get("api_management_credentials").getUri().toString()).isEqualTo("http://api-management.internal:9002");
        assertThat(byId.get("payment_accounts").getUri().toString()).isEqualTo("http://payment.internal:9003");
        assertThat(byId.get("payment_payments").getUri().toString()).isEqualTo("http://payment.internal:9003");
        assertThat(byId.get("payment_transactions").getUri().toString()).isEqualTo("http://payment.internal:9003");
        assertThat(byId.get("managed_api_invocation").getUri().toString()).isEqualTo("http://managed.internal:9004");
    }

    @Test
    void overriddenUpstreamPortsStillDoNotPointBackToTheGateway() {
        assertThat(routes().values())
                .allSatisfy(route -> assertThat(route.getUri().getPort()).isNotEqualTo(8080));
    }
}