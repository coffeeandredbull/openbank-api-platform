package com.openbank.apimanagement.subscription;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/subscription-tiers")
public class SubscriptionTierController {

    private final SubscriptionTierService subscriptionTierService;

    public SubscriptionTierController(SubscriptionTierService subscriptionTierService) {
        this.subscriptionTierService = subscriptionTierService;
    }

    @PatchMapping("/{tierId}")
    public SubscriptionTierResponse update(
            @PathVariable Long tierId,
            @Valid @RequestBody UpdateSubscriptionTierRequest request) {
        return subscriptionTierService.update(tierId, request);
    }
}
