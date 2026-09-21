package com.openbank.apimanagement.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = ContextPathValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidContextPath {

    String message() default "contextPath must start with '/' and must not contain spaces, '?', or '#'";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}