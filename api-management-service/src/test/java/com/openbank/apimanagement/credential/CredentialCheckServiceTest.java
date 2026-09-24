package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.application.Application;
import com.openbank.apimanagement.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CredentialCheckServiceTest {

    @Mock
    private CredentialRepository credentialRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SubscriptionService subscriptionService;

    @InjectMocks
    private CredentialCheckService credentialCheckService;

    private static final String AUTHORIZATION = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("client-abc:super-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void validCredentialsReturnTheApplicationAndSubscription() {
        Application application = new Application("Payments App", "desc", 42L);
        Credential credential = new Credential(application, "client-abc", "hashed-secret");
        when(credentialRepository.findByClientId("client-abc")).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("super-secret", "hashed-secret")).thenReturn(true);
        when(subscriptionService.isSubscribedByApplication(application.getId(), "/payments", "v1")).thenReturn(true);

        CredentialCheckResponse response = credentialCheckService.check(AUTHORIZATION, "/payments", "v1");

        assertThat(response).isEqualTo(CredentialCheckResponse.authenticated(application.getId(), 42L, true));
    }

    @Test
    void revokedCredentialIsUnauthorized() {
        Credential credential = new Credential(new Application("Payments App", "desc", 42L), "client-abc", "hashed-secret");
        setStatus(credential, CredentialStatus.REVOKED);
        when(credentialRepository.findByClientId("client-abc")).thenReturn(Optional.of(credential));

        CredentialCheckResponse response = credentialCheckService.check(AUTHORIZATION, "/payments", "v1");

        assertThat(response).isEqualTo(CredentialCheckResponse.unauthorized());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void unknownClientIdIsUnauthorized() {
        when(credentialRepository.findByClientId("client-abc")).thenReturn(Optional.empty());

        CredentialCheckResponse response = credentialCheckService.check(AUTHORIZATION, "/payments", "v1");

        assertThat(response).isEqualTo(CredentialCheckResponse.unauthorized());
    }

    @Test
    void wrongClientSecretIsUnauthorized() {
        Credential credential = new Credential(new Application("Payments App", "desc", 42L), "client-abc", "hashed-secret");
        when(credentialRepository.findByClientId("client-abc")).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("super-secret", "hashed-secret")).thenReturn(false);

        CredentialCheckResponse response = credentialCheckService.check(AUTHORIZATION, "/payments", "v1");

        assertThat(response).isEqualTo(CredentialCheckResponse.unauthorized());
    }

    @Test
    void missingAuthorizationHeaderIsUnauthorized() {
        CredentialCheckResponse response = credentialCheckService.check(null, "/payments", "v1");

        assertThat(response).isEqualTo(CredentialCheckResponse.unauthorized());
    }

    @Test
    void nonBasicAuthorizationSchemeIsUnauthorized() {
        CredentialCheckResponse response = credentialCheckService.check("Bearer token-123", "/payments", "v1");

        assertThat(response).isEqualTo(CredentialCheckResponse.unauthorized());
    }

    @Test
    void malformedBase64IsUnauthorized() {
        CredentialCheckResponse response = credentialCheckService.check("Basic !!!not-base64!!!", "/payments", "v1");

        assertThat(response).isEqualTo(CredentialCheckResponse.unauthorized());
    }

    @Test
    void missingColonSeparatorIsUnauthorized() {
        String noColon = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("no-colon-here".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        CredentialCheckResponse response = credentialCheckService.check(noColon, "/payments", "v1");

        assertThat(response).isEqualTo(CredentialCheckResponse.unauthorized());
    }

    private void setStatus(Credential credential, CredentialStatus status) {
        try {
            java.lang.reflect.Field field = Credential.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(credential, status);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}