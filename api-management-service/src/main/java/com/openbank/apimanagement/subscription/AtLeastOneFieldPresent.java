package com.openbank.apimanagement.subscription;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = AtLeastOneFieldPresentValidator.class)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AtLeastOneFieldPresent {

    String message() default "at least one field must be supplied";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
