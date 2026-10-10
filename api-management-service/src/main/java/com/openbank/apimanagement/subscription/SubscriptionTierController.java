package com.openbank.apimanagement.subscription;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/subscription-tiers")
public class SubscriptionTierController {

    private final SubscriptionTierService subscriptionTierService;

    public SubscriptionTierController(SubscriptionTierService subscriptionTierService) {
        this.subscriptionTierService = subscriptionTierService;
    }

    @PostMapping
    public ResponseEntity<SubscriptionTierResponse> create(
            @Valid @RequestBody CreateSubscriptionTierRequest request) {
        SubscriptionTierResponse created = subscriptionTierService.create(request);
        return ResponseEntity.created(URI.create("/subscription-tiers/" + created.id())).body(created);
    }

    @GetMapping
    public List<SubscriptionTierResponse> list() {
        return subscriptionTierService.list();
    }

    @PatchMapping("/{tierId}")
    public SubscriptionTierResponse update(
            @PathVariable Long tierId,
            @Valid @RequestBody UpdateSubscriptionTierRequest request) {
        return subscriptionTierService.update(tierId, request);
    }

    @DeleteMapping("/{tierId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long tierId) {
        subscriptionTierService.delete(tierId);
    }
}
