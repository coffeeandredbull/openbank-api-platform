package com.openbank.gateway;

import com.openbank.gateway.auth.TrustedIdentityHeaders;
import com.openbank.gateway.filter.TrustedIdentityHeaderSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedIdentityHeaderSanitizerTest {

    private final TrustedIdentityHeaderSanitizer sanitizer = new TrustedIdentityHeaderSanitizer();

    @Test
    void headerNameConstantsMatchThePhase22Specification() {
        assertThat(TrustedIdentityHeaders.USER_ID).isEqualTo("X-User-Id");
        assertThat(TrustedIdentityHeaders.ROLES).isEqualTo("X-Roles");
        assertThat(TrustedIdentityHeaders.APPLICATION_ID).isEqualTo("X-Application-Id");
        assertThat(TrustedIdentityHeaders.CLIENT_ID).isEqualTo("X-Client-Id");
        assertThat(TrustedIdentityHeaders.allHeaderNames())
                .containsExactly("X-User-Id", "X-Roles", "X-Application-Id", "X-Client-Id");
    }

    @Test
    void allFourTrustedHeadersAreRemoved() {
        ServerWebExchange exchange = exchangeWith(
                "/runtime/apis/payments/v1/accounts",
                header("X-User-Id", "999"),
                header("X-Roles", "DEVELOPER"),
                header("X-Application-Id", "7"),
                header("X-Client-Id", "client-abc"),
                header("X-Normal", "kept"));

        ServerWebExchange sanitized = sanitizer.sanitize(exchange);

        assertThat(sanitized.getRequest().getHeaders())
                .doesNotContainKeys(
                        TrustedIdentityHeaders.USER_ID,
                        TrustedIdentityHeaders.ROLES,
                        TrustedIdentityHeaders.APPLICATION_ID,
                        TrustedIdentityHeaders.CLIENT_ID)
                .containsEntry("X-Normal", List.of("kept"));
    }

    @Test
    void everyNormalHeaderIsPreserved() {
        ServerWebExchange exchange = exchangeWith(
                "/runtime/apis/payments/v1/accounts",
                header("X-Request-Id", "req-1"),
                header("Accept", "application/json"),
                header("X-User-Id", "999"),
                header("Authorization", "Bearer abc"));

        ServerWebExchange sanitized = sanitizer.sanitize(exchange);

        assertThat(sanitized.getRequest().getHeaders().getFirst("X-Request-Id")).isEqualTo("req-1");
        assertThat(sanitized.getRequest().getHeaders().getFirst("Accept")).isEqualTo("application/json");
        assertThat(sanitized.getRequest().getHeaders()).doesNotContainKey(TrustedIdentityHeaders.USER_ID);
    }

    @Test
    void authorizationHeaderIsNeverRemoved() {
        ServerWebExchange bearer = sanitizer.sanitize(exchangeWith(
                "/runtime/apis/payments/v1/accounts",
                header("Authorization", "Bearer some-token"),
                header("X-User-Id", "999")));
        ServerWebExchange basic = sanitizer.sanitize(exchangeWith(
                "/runtime/apis/payments/v1/accounts",
                header("Authorization", "Basic Y2xpZW50OnNlY3JldA=="),
                header("X-Client-Id", "client-abc")));

        assertThat(bearer.getRequest().getHeaders().getFirst("Authorization")).isEqualTo("Bearer some-token");
        assertThat(basic.getRequest().getHeaders().getFirst("Authorization")).isEqualTo("Basic Y2xpZW50OnNlY3JldA==");
    }

    @Test
    void methodPathQueryAndBodyAreNotModifiedByTheDecoration() {
        MockServerHttpRequest.BodyBuilder builder = MockServerHttpRequest.post(
                        "/runtime/apis/payments/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .queryParam("page", "2")
                .queryParam("q", "a b")
                .header("X-User-Id", "999")
                .header("X-Normal", "kept");
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.body("{\"hello\":1}"));

        ServerWebExchange sanitized = sanitizer.sanitize(exchange);
        ServerHttpRequest request = sanitized.getRequest();

        assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(request.getURI().getRawPath()).isEqualTo("/runtime/apis/payments/v1/accounts");
        assertThat(request.getURI().getRawQuery()).isEqualTo("page=2&q=a%20b");
        assertThat(request.getQueryParams())
                .containsEntry("page", List.of("2"))
                .containsEntry("q", List.of("a b"));
        assertThat(bodyAsString(request)).isEqualTo("{\"hello\":1}");
        assertThat(request.getHeaders()).doesNotContainKey(TrustedIdentityHeaders.USER_ID);
    }

    @Test
    void repeatedApplicationIsIdempotent() {
        ServerWebExchange exchange = exchangeWith(
                "/runtime/apis/payments/v1/accounts",
                header("X-User-Id", "999"),
                header("X-Roles", "DEVELOPER"),
                header("X-Application-Id", "7"),
                header("X-Client-Id", "client-abc"),
                header("X-Normal", "kept"));

        ServerWebExchange once = sanitizer.sanitize(exchange);
        ServerWebExchange twice = sanitizer.sanitize(once);

        assertThat(twice.getRequest().getHeaders())
                .isEqualTo(once.getRequest().getHeaders())
                .doesNotContainKeys(
                        TrustedIdentityHeaders.USER_ID,
                        TrustedIdentityHeaders.ROLES,
                        TrustedIdentityHeaders.APPLICATION_ID,
                        TrustedIdentityHeaders.CLIENT_ID)
                .containsEntry("X-Normal", List.of("kept"));
    }

    @Test
    void differentlyCasedClientSuppliedValuesAreAlsoRemoved() {
        ServerWebExchange exchange = exchangeWith(
                "/runtime/apis/payments/v1/accounts",
                header("x-user-id", "999"),
                header("X-ROLES", "DEVELOPER"));

        ServerWebExchange sanitized = sanitizer.sanitize(exchange);

        assertThat(sanitized.getRequest().getHeaders())
                .doesNotContainKeys("x-user-id", "X-ROLES");
    }

    private static MockServerWebExchange exchangeWith(String path, Header... headers) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        for (Header header : headers) {
            builder.header(header.name(), header.value());
        }
        return MockServerWebExchange.from(builder.build());
    }

    private static Header header(String name, String value) {
        return new Header(name, value);
    }

    private static String bodyAsString(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody())
                .map(TrustedIdentityHeaderSanitizerTest::dataBufferToBytes)
                .block();
    }

    private static String dataBufferToBytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private record Header(String name, String value) {
    }
}