package com.openbank.apimanagement.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ContextPathValidator implements ConstraintValidator<ValidContextPath, String> {

    private static final char QUERY = '?';
    private static final char FRAGMENT = '#';

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.length() < 2 || value.charAt(0) != '/') {
            return false;
        }
        boolean hasSegment = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '/') {
                hasSegment = true;
            }
            if (Character.isWhitespace(c)) {
                return false;
            }
        }
        if (!hasSegment) {
            return false;
        }
        if (value.indexOf(QUERY) >= 0 || value.indexOf(FRAGMENT) >= 0) {
            return false;
        }
        return true;
    }
}