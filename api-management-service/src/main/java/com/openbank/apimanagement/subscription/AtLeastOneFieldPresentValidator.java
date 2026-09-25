package com.openbank.apimanagement.subscription;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class AtLeastOneFieldPresentValidator implements ConstraintValidator<AtLeastOneFieldPresent, UpdateSubscriptionTierRequest> {

    @Override
    public boolean isValid(UpdateSubscriptionTierRequest request, ConstraintValidatorContext context) {
        return request != null
                && (request.name() != null
                || request.description() != null
                || request.requestsPerWindow() != null
                || request.windowSeconds() != null);
    }
}
