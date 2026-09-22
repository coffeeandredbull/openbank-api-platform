package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.auth.JwtIdentity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/subscription-check")
public class SubscriptionCheckController {

    private final SubscriptionService subscriptionService;

    public SubscriptionCheckController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping
    public SubscriptionCheckResponse check(
            @AuthenticationPrincipal JwtIdentity identity,
            @RequestParam String contextPath,
            @RequestParam String version) {
        boolean subscribed = subscriptionService.isSubscribed(identity.userId(), contextPath, version);
        return new SubscriptionCheckResponse(subscribed);
    }

    public record SubscriptionCheckResponse(boolean subscribed) {
    }
}