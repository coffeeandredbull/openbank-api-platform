package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.auth.JwtIdentity;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public ResponseEntity<SubscriptionResponse> create(
            @AuthenticationPrincipal JwtIdentity identity,
            @Valid @RequestBody CreateSubscriptionRequest request) {
        SubscriptionResponse created = subscriptionService.create(identity.userId(), request);
        return ResponseEntity.created(URI.create("/subscriptions/" + created.id())).body(created);
    }

    @GetMapping("/{subscriptionId}")
    public SubscriptionResponse get(
            @AuthenticationPrincipal JwtIdentity identity,
            @PathVariable Long subscriptionId) {
        return subscriptionService.get(subscriptionId, identity.userId());
    }

    @GetMapping
    public List<SubscriptionResponse> list(@AuthenticationPrincipal JwtIdentity identity) {
        return subscriptionService.list(identity.userId());
    }

    @PatchMapping("/{subscriptionId}/status")
    public SubscriptionResponse updateStatus(
            @PathVariable Long subscriptionId,
            @Valid @RequestBody UpdateSubscriptionStatusRequest request) {
        return subscriptionService.changeStatus(subscriptionId, request);
    }
}