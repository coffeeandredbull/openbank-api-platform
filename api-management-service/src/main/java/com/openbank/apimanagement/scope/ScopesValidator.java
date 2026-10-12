package com.openbank.apimanagement.scope;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

public class ScopesValidator implements ConstraintValidator<ValidScopes, List<String>> {

    @Override
    public boolean isValid(List<String> value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            Scopes.canonicalize(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
