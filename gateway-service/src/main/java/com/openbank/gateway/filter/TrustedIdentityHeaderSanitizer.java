package com.openbank.gateway.filter;

import com.openbank.gateway.auth.TrustedIdentityHeaders;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Reusable gateway-side guard for the Phase 22 trusted identity headers
 * ({@code X-User-Id}, {@code X-Roles}, {@code X-Application-Id},
 * {@code X-Client-Id}). It strips any client-supplied values for these headers
 * from an inbound request by decorating the request; every other header is
 * preserved untouched. Both authentication filters will use this sanitizer so
 * that client-supplied identity headers can never reach a managed API or be
 * mistaken for gateway-generated context.
 */
@Component
public class TrustedIdentityHeaderSanitizer {

    /**
     * Returns an exchange wrapper whose request no longer carries any of the
     * trusted identity headers. The supplied exchange is not mutated; all
     * other headers, the method, path, query, and body are preserved.
     */
    public ServerWebExchange sanitize(ServerWebExchange exchange) {
        ServerHttpRequestDecorator sanitized = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                TrustedIdentityHeaders.allHeaderNames().forEach(headers::remove);
                return HttpHeaders.readOnlyHttpHeaders(headers);
            }
        };
        return exchange.mutate().request(sanitized).build();
    }
}