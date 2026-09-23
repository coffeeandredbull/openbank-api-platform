package com.openbank.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.gateway.auth.ClientCredentialIdentity;
import com.openbank.gateway.auth.JwtIdentity;
import com.openbank.gateway.auth.UserRole;
import com.openbank.gateway.filter.ClientCredentialAuthenticationFilter;
import com.openbank.gateway.filter.JwtAuthenticationFilter;
import com.openbank.gateway.filter.RateLimitingFilter;
import com.openbank.gateway.ratelimit.RateLimitService;
import com.openbank.gateway.ratelimit.RateLimitService.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTest {

    record Call(long userId, String contextPath, String version) {
    }

    record AppCall(long applicationId, String contextPath, String version) {
    }

    record Result(boolean forwarded, HttpStatusCode status, String body, String retryAfter) {
    }

    static final class FakeRateLimitService implements RateLimitService {

        final List<Call> calls = new ArrayList<>();
        final List<AppCall> appCalls = new ArrayList<>();
        State state = State.ALLOWED;
        long retryAfter;

        @Override
        public Decision evaluate(long userId, String contextPath, String version) {
            calls.add(new Call(userId, contextPath, version));
            return decision();
        }

        @Override
        public Decision evaluateForApplication(long applicationId, String contextPath, String version) {
            appCalls.add(new AppCall(applicationId, contextPath, version));
            return decision();
        }

        private Decision decision() {
            return switch (state) {
                case ALLOWED -> Decision.allowed();
                case DENIED -> Decision.denied(retryAfter);
                case UNAVAILABLE -> Decision.unavailable();
            };
        }
    }

    private final FakeRateLimitService rateLimitService = new FakeRateLimitService();
    private final RateLimitingFilter filter = new RateLimitingFilter(rateLimitService, new ObjectMapper());

    @BeforeEach
    void reset() {
        rateLimitService.calls.clear();
        rateLimitService.appCalls.clear();
        rateLimitService.state = State.ALLOWED;
        rateLimitService.retryAfter = 0;
    }

    private Result call(String path, JwtIdentity identity) {
        return call(MockServerHttpRequest.get(path), identity);
    }

    private Result call(MockServerHttpRequest.BaseBuilder<?> request, JwtIdentity identity) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        if (identity != null) {
            exchange.getAttributes().put(JwtAuthenticationFilter.IDENTITY_ATTRIBUTE, identity);
        }
        return run(exchange);
    }

    private Result call(MockServerHttpRequest.BaseBuilder<?> request, long applicationId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        exchange.getAttributes().put(ClientCredentialAuthenticationFilter.CLIENT_CREDENTIAL_ATTRIBUTE,
                new ClientCredentialIdentity("client-abc", applicationId, 42L));
        return run(exchange);
    }

    private Result run(MockServerWebExchange exchange) {
        AtomicBoolean forwarded = new AtomicBoolean(false);
        AtomicLong count = new AtomicLong(0);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            count.incrementAndGet();
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        String body = "";
        if (status != null) {
            body = exchange.getResponse().getBodyAsString().block();
        }
        String retryAfter = exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        return new Result(forwarded.get(), status, body, retryAfter);
    }

    private static JwtIdentity admin() {
        return new JwtIdentity(1L, UserRole.ADMIN);
    }

    @Test
    void nonRuntimePathsAreForwardedWithoutContactingTheRateLimiter() {
        Result result = call("/accounts/1", null);
        assertThat(result.forwarded()).isTrue();
        assertThat(rateLimitService.calls).isEmpty();
    }

    @Test
    void allowedRequestIsForwardedWithTheAuthenticatedJwtSubject() {
        Result result = call("/runtime/apis/payments/v1/accounts", new JwtIdentity(42L, UserRole.DEVELOPER));
        assertThat(result.forwarded()).isTrue();
        assertThat(rateLimitService.calls).containsExactly(new Call(42L, "/payments", "v1"));
    }

    @Test
    void requestOverTheLimitReturns429WithRetryAfterAndIsNotForwarded() {
        rateLimitService.state = State.DENIED;
        rateLimitService.retryAfter = 42;
        Result result = call("/runtime/apis/payments/v1/accounts", admin());
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result.retryAfter()).isEqualTo("42");
        assertThat(result.body())
                .contains("\"status\":429")
                .contains("\"error\":\"Too Many Requests\"")
                .contains("\"code\":\"RATE_LIMIT_EXCEEDED\"")
                .contains("\"message\":\"Rate limit exceeded\"")
                .contains("\"path\":\"/runtime/apis/payments/v1/accounts\"")
                .contains("\"fieldErrors\":{}");
    }

    @Test
    void unavailableRateLimiterReturns503AndIsNotForwarded() {
        rateLimitService.state = State.UNAVAILABLE;
        Result result = call("/runtime/apis/payments/v1/accounts", admin());
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.body())
                .contains("\"status\":503")
                .contains("\"code\":\"RATE_LIMIT_SERVICE_UNAVAILABLE\"")
                .contains("\"message\":\"Rate limiting service unavailable\"");
    }

    @Test
    void missingIdentityFailsClosedWithoutConsumingARateLimitSlot() {
        Result result = call("/runtime/apis/payments/v1/accounts", null);
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rateLimitService.calls).isEmpty();
    }

    @Test
    void malformedRuntimePathFailsClosedWithoutConsumingARateLimitSlot() {
        Result result = call("/runtime/apis/payments", admin());
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rateLimitService.calls).isEmpty();
    }

    @Test
    void rawAuthorizationHeaderIsNeverUsedAsTheIdentity() {
        String userOneToken = GatewayTestJwt.admin();
        Result result = call(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userOneToken),
                new JwtIdentity(99L, UserRole.DEVELOPER));
        assertThat(result.forwarded()).isTrue();
        assertThat(rateLimitService.calls).containsExactly(new Call(99L, "/payments", "v1"));
    }

    @Test
    void adminIsRateLimitedExactlyLikeAnyOtherUser() {
        rateLimitService.state = State.DENIED;
        Result result = call("/runtime/apis/payments/v1/accounts", admin());
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rateLimitService.calls).containsExactly(new Call(1L, "/payments", "v1"));
    }

    @Test
    void errorResponsesDoNotExposeTokensHostsOrImplementationDetails() {
        rateLimitService.state = State.DENIED;
        String token = GatewayTestJwt.admin();
        Result result = call(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token),
                admin());
        assertThat(result.body())
                .doesNotContain(token)
                .doesNotContain("redis")
                .doesNotContain("localhost")
                .doesNotContain("127.0.0.1")
                .doesNotContain("http://")
                .doesNotContain("com.openbank");

        rateLimitService.state = State.UNAVAILABLE;
        Result unavailable = call("/runtime/apis/payments/v1/accounts", admin());
        assertThat(unavailable.body())
                .doesNotContain(token)
                .doesNotContain("localhost")
                .doesNotContain("127.0.0.1")
                .doesNotContain("com.openbank");
    }

    @Test
    void applicationClientCredentialsAreRateLimitedPerApplicationAndTarget() {
        Result result = call(MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts"), 12L);
        assertThat(result.forwarded()).isTrue();
        assertThat(rateLimitService.appCalls).containsExactly(new AppCall(12L, "/payments", "v1"));
        assertThat(rateLimitService.calls).isEmpty();
    }

    @Test
    void applicationRequestOverTheLimitReturns429AndIsNotForwarded() {
        rateLimitService.state = State.DENIED;
        rateLimitService.retryAfter = 15;
        Result result = call(MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts"), 12L);
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result.retryAfter()).isEqualTo("15");
        assertThat(result.body())
                .contains("\"status\":429")
                .contains("\"code\":\"RATE_LIMIT_EXCEEDED\"")
                .contains("\"message\":\"Rate limit exceeded\"");
    }

    @Test
    void distinctApplicationsProduceDistinctRateLimitCalls() {
        call(MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts"), 12L);
        call(MockServerHttpRequest.get("/runtime/apis/accounts/v2/balances"), 99L);
        assertThat(rateLimitService.appCalls).containsExactly(
                new AppCall(12L, "/payments", "v1"),
                new AppCall(99L, "/accounts", "v2"));
    }

    @Test
    void applicationFlowIgnoresTheJwtIdentityWhenBothArePresent() {
        String token = GatewayTestJwt.admin();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());
        exchange.getAttributes().put(JwtAuthenticationFilter.IDENTITY_ATTRIBUTE,
                new JwtIdentity(5L, UserRole.ADMIN));
        exchange.getAttributes().put(ClientCredentialAuthenticationFilter.CLIENT_CREDENTIAL_ATTRIBUTE,
                new ClientCredentialIdentity("client-abc", 12L, 42L));
        run(exchange);
        assertThat(rateLimitService.appCalls).containsExactly(new AppCall(12L, "/payments", "v1"));
        assertThat(rateLimitService.calls).isEmpty();
    }

    @Test
    void applicationErrorBodiesDoNotExposeTheClientSecretOrClientId() {
        rateLimitService.state = State.DENIED;
        String clientId = "client-abc";
        String secret = "super-secret-value";
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        Result result = call(MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basic), 12L);
        assertThat(result.body())
                .doesNotContain(secret)
                .doesNotContain(clientId)
                .doesNotContain("Basic ");
    }
}