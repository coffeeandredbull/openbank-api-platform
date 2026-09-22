package com.openbank.apimanagement.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiNotFoundProducesStructured404() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleApiNotFound(new ApiNotFoundException(7L), request("GET", "/apis/7"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.path()).isEqualTo("/apis/7");
        assertThat(body.code()).isEqualTo("API_NOT_FOUND");
        assertThat(body.message()).contains("7");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    void duplicateContextPathProducesStructured409() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleContextPathAlreadyExists(
                        new ContextPathAlreadyExistsException("/payments"), request("POST", "/apis"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.error()).isEqualTo("Conflict");
        assertThat(body.code()).isEqualTo("CONTEXT_PATH_ALREADY_EXISTS");
        assertThat(body.message()).contains("/payments");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.toString())
                .doesNotContain("unique constraint")
                .doesNotContain("SQL")
                .doesNotContain("DataIntegrityViolation");
    }

    @Test
    void apiVersionNotFoundProducesStructured404() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleApiVersionNotFound(
                        new ApiVersionNotFoundException(1L, 9L), request("GET", "/apis/1/versions/9"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.path()).isEqualTo("/apis/1/versions/9");
        assertThat(body.code()).isEqualTo("API_VERSION_NOT_FOUND");
        assertThat(body.message()).contains("9").contains("1");
        assertThat(body.fieldErrors()).isEmpty();
    }

    @Test
    void duplicateApiVersionProducesStructured409() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleApiVersionAlreadyExists(
                        new ApiVersionAlreadyExistsException(1L, "v1"), request("POST", "/apis/1/versions"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.error()).isEqualTo("Conflict");
        assertThat(body.code()).isEqualTo("API_VERSION_ALREADY_EXISTS");
        assertThat(body.message()).contains("v1").contains("1");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.toString())
                .doesNotContain("unique constraint")
                .doesNotContain("SQL")
                .doesNotContain("DataIntegrityViolation");
    }

    @Test
    void malformedJsonProducesSafe400WithoutInternals() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character (',' (code 44)): expected a valid value",
                new IllegalArgumentException("internal parser detail"));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUnreadable(ex, request("POST", "/apis"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body.code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(body.message()).isEqualTo("Request body is missing or malformed");
        assertThat(body.toString())
                .doesNotContain("Unexpected character")
                .doesNotContain("internal parser detail")
                .doesNotContain("IllegalArgumentException");
    }

    @Test
    void invalidFormatProduces400IdentifyingTheField() {
        InvalidFormatException invalidFormat = InvalidFormatException.from(
                null, "Cannot deserialize value of type Long from String \"abc\"", "abc", Long.class);
        invalidFormat.prependPath(new Object(), "id");
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error", invalidFormat);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUnreadable(ex, request("POST", "/apis"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.fieldErrors()).containsKey("id");
        assertThat(body.toString())
                .doesNotContain("Cannot deserialize")
                .doesNotContain("abc");
    }

    @Test
    void applicationNotFoundProducesStructured404() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleApplicationNotFound(
                        new ApplicationNotFoundException(7L), request("GET", "/applications/7"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.path()).isEqualTo("/applications/7");
        assertThat(body.code()).isEqualTo("APPLICATION_NOT_FOUND");
        assertThat(body.message()).contains("7");
        assertThat(body.fieldErrors()).isEmpty();
    }

    @Test
    void invalidLifecycleTransitionProducesStructured409() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleInvalidLifecycleTransition(
                        new InvalidLifecycleTransitionException(
                                com.openbank.apimanagement.api.ApiVersionLifecycle.PUBLISHED,
                                com.openbank.apimanagement.api.ApiVersionLifecycle.CREATED),
                        request("PATCH", "/apis/1/versions/5/lifecycle"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.error()).isEqualTo("Conflict");
        assertThat(body.path()).isEqualTo("/apis/1/versions/5/lifecycle");
        assertThat(body.code()).isEqualTo("INVALID_LIFECYCLE_TRANSITION");
        assertThat(body.message()).contains("PUBLISHED").contains("CREATED");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.toString())
                .doesNotContain("EnumMap")
                .doesNotContain("AllowedTransitions")
                .doesNotContain("at com.openbank");
    }

    @Test
    void unsupportedMethodProduces405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("PATCH", java.util.List.of("GET", "POST"));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleMethodNotSupported(ex, request("PATCH", "/apis"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().message()).contains("PATCH");
    }

    @Test
    void unexpectedExceptionProducesGeneric500WithoutInternalsOrSecrets() {
        RuntimeException ex = new RuntimeException(
                "could not execute statement; SQL [insert into apis]; constraint; jdbc secret",
                new IllegalStateException("/home/openbank/api-management-service/secret.properties"));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUnexpected(ex, request("POST", "/apis"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).isEqualTo("An unexpected error occurred");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.toString())
                .doesNotContain("insert into apis")
                .doesNotContain("secret.properties")
                .doesNotContain("could not execute statement")
                .doesNotContain("at com.openbank")
                .doesNotContain("java.lang")
                .doesNotContain("Caused by");
    }

    @Test
    void subscriptionNotFoundProducesStructured404() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleSubscriptionNotFound(
                        new SubscriptionNotFoundException(7L), request("GET", "/subscriptions/7"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.path()).isEqualTo("/subscriptions/7");
        assertThat(body.code()).isEqualTo("APPLICATION_SUBSCRIPTION_NOT_FOUND");
        assertThat(body.message()).contains("7");
        assertThat(body.fieldErrors()).isEmpty();
    }

    @Test
    void duplicateSubscriptionProducesStructured409() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleSubscriptionAlreadyExists(
                        new SubscriptionAlreadyExistsException(1L, 2L), request("POST", "/subscriptions"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.error()).isEqualTo("Conflict");
        assertThat(body.code()).isEqualTo("SUBSCRIPTION_ALREADY_EXISTS");
        assertThat(body.message()).contains("1").contains("2");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.toString())
                .doesNotContain("unique constraint")
                .doesNotContain("SQL")
                .doesNotContain("DataIntegrityViolation");
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}