package com.openbank.identity.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.openbank.identity.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void userNotFoundProducesStructured404() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleUserNotFound(new UserNotFoundException(7L), request("GET", "/users/7"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.path()).isEqualTo("/users/7");
        assertThat(body.code()).isEqualTo("USER_NOT_FOUND");
        assertThat(body.message()).contains("7");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    void duplicateEmailProducesStructured409() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleEmailAlreadyExists(new EmailAlreadyExistsException("dup@example.com"), request("POST", "/users"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("EMAIL_ALREADY_EXISTS");
        assertThat(response.getBody().fieldErrors()).isEmpty();
    }

    @Test
    void malformedJsonProducesSafe400WithoutInternals() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character (',' (code 44)): expected a valid value",
                new IllegalArgumentException("internal parser detail"));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUnreadable(ex, request("POST", "/users"));

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
    void invalidEnumProduces400IdentifyingTheField() {
        InvalidFormatException invalidFormat = InvalidFormatException.from(
                null, "Cannot deserialize value of type UserRole from String \"SUPERUSER\"", "SUPERUSER", UserRole.class);
        invalidFormat.prependPath(new Object(), "role");
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error", invalidFormat);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUnreadable(ex, request("POST", "/users"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.fieldErrors()).containsKey("role");
        assertThat(body.fieldErrors().get("role")).contains("ADMIN").contains("DEVELOPER");
        assertThat(body.toString())
                .doesNotContain("SUPERUSER")
                .doesNotContain("Cannot deserialize");
    }

    @Test
    void unsupportedMethodProduces405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("PATCH", java.util.List.of("GET", "POST"));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleMethodNotSupported(ex, request("PATCH", "/users"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().message()).contains("PATCH");
    }

    @Test
    void unexpectedExceptionProducesGeneric500WithoutInternalsOrSecrets() {
        RuntimeException ex = new RuntimeException(
                "could not execute statement; SQL [insert into users]; constraint; passwordHash=abc123; secret",
                new IllegalStateException("/home/openbank/identity-service/secret.properties"));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUnexpected(ex, request("POST", "/users"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        GlobalExceptionHandler.ErrorResponse body = response.getBody();
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).isEqualTo("An unexpected error occurred");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.toString())
                .doesNotContain("insert into users")
                .doesNotContain("passwordHash")
                .doesNotContain("secret.properties")
                .doesNotContain("could not execute statement")
                .doesNotContain("at com.openbank")
                .doesNotContain("java.lang")
                .doesNotContain("Caused by");
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}