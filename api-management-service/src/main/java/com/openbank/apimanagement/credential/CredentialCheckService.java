package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.application.Application;
import com.openbank.apimanagement.subscription.SubscriptionPolicy;
import com.openbank.apimanagement.subscription.SubscriptionService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class CredentialCheckService {

    private static final String BASIC_PREFIX = "Basic ";

    private final CredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final SubscriptionService subscriptionService;

    public CredentialCheckService(
            CredentialRepository credentialRepository,
            PasswordEncoder passwordEncoder,
            SubscriptionService subscriptionService) {
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.subscriptionService = subscriptionService;
    }

    @Transactional(readOnly = true)
    public CredentialCheckResponse check(String authorization, String contextPath, String version) {
        ClientCredentials clientCredentials = parseBasic(authorization);
        if (clientCredentials == null) {
            return CredentialCheckResponse.unauthorized();
        }
        Credential credential = credentialRepository
                .findByClientId(clientCredentials.clientId())
                .orElse(null);
        if (credential == null) {
            return CredentialCheckResponse.unauthorized();
        }
        if (credential.getStatus() != CredentialStatus.ACTIVE) {
            return CredentialCheckResponse.unauthorized();
        }
        if (credential.getExpiresAt() != null && !Instant.now().isBefore(credential.getExpiresAt())) {
            return CredentialCheckResponse.unauthorized();
        }
        if (!passwordEncoder.matches(clientCredentials.clientSecret(), credential.getClientSecretHash())) {
            return CredentialCheckResponse.unauthorized();
        }
        Application application = credential.getApplication();
        SubscriptionPolicy policy = subscriptionService.findActivePolicyByApplication(
                application.getId(), contextPath, version);
        return CredentialCheckResponse.authenticated(
                application.getId(), application.getOwnerUserId(), policy);
    }

    private ClientCredentials parseBasic(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            return null;
        }
        String encoded = authorization.substring(BASIC_PREFIX.length()).trim();
        if (encoded.isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator <= 0 || separator == decoded.length() - 1) {
                return null;
            }
            return new ClientCredentials(decoded.substring(0, separator), decoded.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record ClientCredentials(String clientId, String clientSecret) {
    }
}