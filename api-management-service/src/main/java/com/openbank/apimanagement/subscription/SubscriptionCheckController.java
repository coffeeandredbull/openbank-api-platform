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
        SubscriptionPolicy policy = subscriptionService.findActivePolicy(identity.userId(), contextPath, version);
        if (policy == null) {
            return SubscriptionCheckResponse.notSubscribed();
        }
        return SubscriptionCheckResponse.subscribed(policy);
    }

    public record SubscriptionCheckResponse(
            boolean subscribed,
            Long tierId,
            String tierName,
            Integer requestsPerWindow,
            Integer windowSeconds) {

        public static SubscriptionCheckResponse notSubscribed() {
            return new SubscriptionCheckResponse(false, null, null, null, null);
        }

        public static SubscriptionCheckResponse subscribed(SubscriptionPolicy policy) {
            return new SubscriptionCheckResponse(
                    true,
                    policy.tierId(),
                    policy.tierName(),
                    policy.requestsPerWindow(),
                    policy.windowSeconds());
        }
    }
}