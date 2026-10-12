package com.openbank.apimanagement.scope;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = ScopesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidScopes {

    String message() default "scopes must be unique tokens of at most 64 characters like 'accounts:read'";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
