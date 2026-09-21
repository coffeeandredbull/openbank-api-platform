package com.openbank.apimanagement.api;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ContextPathValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private final ContextPathValidator validator = new ContextPathValidator();

    @ParameterizedTest
    @ValueSource(strings = {"/payments", "/accounts", "/customer-api", "/customer-api/v2", "/payments/"})
    void validContextPathsAccepted(String value) {
        assertThat(validator.isValid(value, context)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "payments", "/", "/ ", "//", " /payments", "/payments api", "/payments?x=1", "/payments#test", "/pay\nments"})
    void invalidContextPathsRejected(String value) {
        assertThat(validator.isValid(value, context)).isFalse();
    }

    @Test
    void nullValueDelegateToNotBlank() {
        assertThat(validator.isValid(null, context)).isTrue();
    }
}