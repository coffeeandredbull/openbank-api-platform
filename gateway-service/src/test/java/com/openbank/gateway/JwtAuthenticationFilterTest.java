package com.openbank.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.gateway.auth.JwtTokenService;
import com.openbank.gateway.filter.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

import reactor.core.publisher.Mono;

class JwtAuthenticationFilterTest {

    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(
                    new JwtTokenService(GatewayTestJwt.SECRET, Clock.systemUTC()),
                    new ObjectMapper());

    private record Result(boolean forwarded, HttpStatusCode status, String body) {
    }

    private Result call(MockServerHttpRequest.BaseBuilder<?> builder) {
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        String body = "";
        if (status != null && status.value() == 401) {
            body = exchange.getResponse().getBodyAsString().block();
        }
        return new Result(forwarded.get(), status, body);
    }

    @Test
    void postUsersIsPublicWithoutToken() {
        Result result = call(MockServerHttpRequest.post("/users"));
        assertThat(result.forwarded()).isTrue();
        assertThat(result.status()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void postAuthLoginIsPublicWithoutToken() {
        Result result = call(MockServerHttpRequest.post("/auth/login"));
        assertThat(result.forwarded()).isTrue();
    }

    @Test
    void getActuatorHealthIsPublicWithoutToken() {
        Result result = call(MockServerHttpRequest.get("/actuator/health"));
        assertThat(result.forwarded()).isTrue();
    }

    @Test
    void getUsersIsRejectedWithoutToken() {
        Result result = call(MockServerHttpRequest.get("/users"));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deleteUsersIsRejectedBecauseOnlyPostIsPublic() {
        Result result = call(MockServerHttpRequest.delete("/users"));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getUsersSubpathIsRejectedWithoutToken() {
        Result result = call(MockServerHttpRequest.get("/users/me"));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void postAuthLogoutIsRejectedWithoutToken() {
        Result result = call(MockServerHttpRequest.post("/auth/logout"));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getAuthLoginIsRejectedBecauseOnlyPostIsPublic() {
        Result result = call(MockServerHttpRequest.get("/auth/login"));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void apisIsRejectedWithoutToken() {
        Result result = call(MockServerHttpRequest.get("/apis"));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void accountsIsRejectedWithoutToken() {
        Result result = call(MockServerHttpRequest.get("/accounts/1"));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedPathWithValidAdminTokenIsForwarded() {
        Result result = call(MockServerHttpRequest.get("/accounts/1")
                .header("Authorization", "Bearer " + GatewayTestJwt.admin()));
        assertThat(result.forwarded()).isTrue();
        assertThat(result.status()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validTokenStoresTheJwtIdentityAsAnExchangeAttribute() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts")
                        .header("Authorization", "Bearer " + GatewayTestJwt.admin())
                        .build());
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(forwarded).isTrue();
        Object identity = exchange.getAttribute(com.openbank.gateway.filter.JwtAuthenticationFilter.IDENTITY_ATTRIBUTE);
        assertThat(identity).isNotNull();
    }

    @Test
    void runtimeApiPathIsRejectedWithoutToken() {
        Result result = call(MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts"));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTokenIsRejected() {
        Result result = call(MockServerHttpRequest.get("/accounts/1")
                .header("Authorization", "Bearer " + GatewayTestJwt.expiredAdmin()));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedTokenIsRejected() {
        Result result = call(MockServerHttpRequest.get("/accounts/1")
                .header("Authorization", "Bearer not.a.jwt"));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tamperedTokenIsRejected() {
        Result result = call(MockServerHttpRequest.get("/accounts/1")
                .header("Authorization", "Bearer " + GatewayTestJwt.tampered(GatewayTestJwt.admin())));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongAuthorizationSchemeIsRejected() {
        Result result = call(MockServerHttpRequest.get("/accounts/1")
                .header("Authorization", "Basic dXNlcjpwYXNz"));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void emptyBearerTokenIsRejected() {
        Result result = call(MockServerHttpRequest.get("/accounts/1")
                .header("Authorization", "Bearer "));
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectedResponseUsesSafeErrorBody() {
        Result result = call(MockServerHttpRequest.get("/apis"));
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(result.body()).contains("\"status\":401");
        assertThat(result.body()).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(result.body()).contains("\"message\":\"Authentication is required\"");
        assertThat(result.body()).contains("\"path\":\"/apis\"");
        assertThat(result.body()).contains("\"fieldErrors\":{}");
        assertThat(result.body())
                .doesNotContain("InvalidJwtException")
                .doesNotContain("Nimbus")
                .doesNotContain("jose")
                .doesNotContain("java.")
                .doesNotContain("com.openbank");
    }

    @Test
    void rejectedBodyDoesNotExposeTheJwt() {
        String token = GatewayTestJwt.tampered(GatewayTestJwt.admin());
        Result result = call(MockServerHttpRequest.get("/accounts/1")
                .header("Authorization", "Bearer " + token));
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(result.body()).doesNotContain(token);
    }

    @Test
    void unroutedPathIsForwardedWithoutAuthentication() {
        Result result = call(MockServerHttpRequest.get("/no-such-route"));
        assertThat(result.forwarded()).isTrue();
    }

    @Test
    void clientCredentialRequestsSkipJwtAuthenticationEvenWithAnInvalidBearerHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts")
                        .header("Authorization", "Bearer not.a.jwt")
                        .build());
        exchange.getAttributes().put(
                com.openbank.gateway.filter.ClientCredentialAuthenticationFilter.CLIENT_CREDENTIAL_ATTRIBUTE,
                new com.openbank.gateway.auth.ClientCredentialIdentity("client-abc", 12L, 42L));
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(forwarded).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}