package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.application.Application;
import com.openbank.apimanagement.application.ApplicationRepository;
import com.openbank.apimanagement.exception.ApplicationNotFoundException;
import com.openbank.apimanagement.exception.CredentialNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CredentialServiceTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private CredentialRepository credentialRepository;
    private ApplicationRepository applicationRepository;
    private CredentialService credentialService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-22T10:00:00Z");

    @BeforeEach
    void setUp() {
        credentialRepository = org.mockito.Mockito.mock(CredentialRepository.class);
        applicationRepository = org.mockito.Mockito.mock(ApplicationRepository.class);
        credentialService = new CredentialService(credentialRepository, applicationRepository, passwordEncoder);
    }

    @Test
    void createPersistsCredentialAndReturnsClientIdAndPlaintextSecretOnce() {
        Application application = application(10L, 42L);
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L)).thenReturn(Optional.of(application));
        when(credentialRepository.existsByClientId(anyString())).thenReturn(false);
        when(credentialRepository.save(any(Credential.class))).thenAnswer(invocation -> {
            Credential saved = invocation.getArgument(0);
            setField(saved, "id", 1L);
            return saved;
        });

        CredentialCreatedResponse response = credentialService.create(42L, new CreateCredentialRequest(10L));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.applicationId()).isEqualTo(10L);
        assertThat(response.clientId()).isNotNull();
        assertThat(response.clientSecret()).isNotBlank();
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
        assertThat(response.updatedAt()).isEqualTo(response.createdAt());
    }

    @Test
    void generatedClientIdIsUsedForPersistenceAndIsServerSide() {
        Application application = application(10L, 42L);
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L)).thenReturn(Optional.of(application));
        when(credentialRepository.existsByClientId(anyString())).thenReturn(false);
        when(credentialRepository.save(any(Credential.class))).thenAnswer(invocation -> {
            Credential saved = invocation.getArgument(0);
            setField(saved, "id", 1L);
            return saved;
        });

        CredentialCreatedResponse response = credentialService.create(42L, new CreateCredentialRequest(10L));

        Credential persisted = capturedCredential();
        assertThat(persisted.getClientId()).isEqualTo(response.clientId());
        assertThat(persisted.getClientId()).isNotBlank();
        assertThat(persisted.getClientId()).isNotEqualTo("10");
    }

    @Test
    void secretIsHashedBeforePersistenceAndMatchesPlaintext() {
        Application application = application(10L, 42L);
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L)).thenReturn(Optional.of(application));
        when(credentialRepository.existsByClientId(anyString())).thenReturn(false);
        when(credentialRepository.save(any(Credential.class))).thenAnswer(invocation -> {
            Credential saved = invocation.getArgument(0);
            setField(saved, "id", 1L);
            return saved;
        });

        CredentialCreatedResponse response = credentialService.create(42L, new CreateCredentialRequest(10L));

        Credential persisted = capturedCredential();
        assertThat(persisted.getClientSecretHash())
                .isNotEqualTo(response.clientSecret())
                .startsWith("$2");
        assertThat(passwordEncoder.matches(response.clientSecret(), persisted.getClientSecretHash())).isTrue();
        assertThat(passwordEncoder.matches("some-other-secret", persisted.getClientSecretHash())).isFalse();
    }

    @Test
    void createRejectsMissingApplication() {
        when(applicationRepository.findByIdAndOwnerUserId(999L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> credentialService.create(42L, new CreateCredentialRequest(999L)))
                .isInstanceOf(ApplicationNotFoundException.class)
                .hasMessageContaining("999");
        verify(credentialRepository, never()).save(any(Credential.class));
    }

    @Test
    void createRejectsApplicationOwnedByAnotherUser() {
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> credentialService.create(42L, new CreateCredentialRequest(10L)))
                .isInstanceOf(ApplicationNotFoundException.class)
                .hasMessageContaining("10");
        verify(credentialRepository, never()).save(any(Credential.class));
    }

    @Test
    void clientIdCollisionIsRetriedAtServiceLevel() {
        Application application = application(10L, 42L);
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L)).thenReturn(Optional.of(application));
        when(credentialRepository.existsByClientId(anyString()))
                .thenReturn(true)
                .thenReturn(false);
        when(credentialRepository.save(any(Credential.class))).thenAnswer(invocation -> {
            Credential saved = invocation.getArgument(0);
            setField(saved, "id", 1L);
            return saved;
        });

        CredentialCreatedResponse response = credentialService.create(42L, new CreateCredentialRequest(10L));

        assertThat(response.clientId()).isNotNull();
        verify(credentialRepository, times(1)).save(any(Credential.class));
    }

    @Test
    void clientIdDatabaseRaceRegeneratesAndRetries() {
        Application application = application(10L, 42L);
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L)).thenReturn(Optional.of(application));
        when(credentialRepository.existsByClientId(anyString())).thenReturn(false);
        when(credentialRepository.save(any(Credential.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement; unique constraint"))
                .thenAnswer(invocation -> {
                    Credential saved = invocation.getArgument(0);
                    setField(saved, "id", 1L);
                    return saved;
                });

        CredentialCreatedResponse response = credentialService.create(42L, new CreateCredentialRequest(10L));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.clientSecret()).isNotBlank();
        ArgumentCaptor<Credential> captor = ArgumentCaptor.forClass(Credential.class);
        verify(credentialRepository, times(2)).save(captor.capture());
        List<Credential> allSaved = captor.getAllValues();
        assertThat(allSaved.get(0).getClientId()).isNotEqualTo(allSaved.get(1).getClientId());
    }

    @Test
    void getReturnsCredentialForOwnedApplication() {
        Credential stored = credential(1L, 10L, "client-123", "$2a$10$hashvalue");
        when(credentialRepository.findByIdAndApplication_OwnerUserId(1L, 42L))
                .thenReturn(Optional.of(stored));

        CredentialResponse response = credentialService.get(1L, 42L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.applicationId()).isEqualTo(10L);
        assertThat(response.clientId()).isEqualTo("client-123");
    }

    @Test
    void getThrowsNotFoundWhenCredentialBelongsToAnotherUsersApplication() {
        when(credentialRepository.findByIdAndApplication_OwnerUserId(1L, 7L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> credentialService.get(1L, 7L))
                .isInstanceOf(CredentialNotFoundException.class)
                .hasMessageContaining("1");
    }

    @Test
    void listReturnsOnlyOwnedCredentialsOrderedByIdAsc() {
        Credential first = credential(1L, 10L, "client-1", "$2a$10$abc");
        Credential second = credential(2L, 10L, "client-2", "$2a$10$def");
        when(credentialRepository.findByApplication_OwnerUserIdOrderByIdAsc(42L))
                .thenReturn(List.of(first, second));

        List<CredentialResponse> responses = credentialService.list(42L);

        assertThat(responses).hasSize(2);
        assertThat(responses.stream().map(CredentialResponse::clientId).toList())
                .containsExactly("client-1", "client-2");
        verify(credentialRepository).findByApplication_OwnerUserIdOrderByIdAsc(42L);
    }

    @Test
    void normalResponseDtoNeverExposesSecretOrHash() throws Exception {
        Credential stored = credential(1L, 10L, "client-123", "$2a$10$the-stored-hash-value");

        String json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(CredentialResponse.from(stored));

        assertThat(json)
                .contains("clientId")
                .doesNotContain("the-stored-hash-value")
                .doesNotContain("clientSecret")
                .doesNotContain("clientSecretHash");
    }

    private Credential capturedCredential() {
        org.mockito.ArgumentCaptor<Credential> captor =
                org.mockito.ArgumentCaptor.forClass(Credential.class);
        verify(credentialRepository).save(captor.capture());
        return captor.getValue();
    }

    private Application application(Long id, Long ownerUserId) {
        Application application = new Application("My App", "Demo", ownerUserId);
        setField(application, "id", id);
        setField(application, "createdAt", TIMESTAMP);
        setField(application, "updatedAt", TIMESTAMP);
        return application;
    }

    private Credential credential(Long id, Long applicationId, String clientId, String clientSecretHash) {
        Credential credential = new Credential(application(applicationId, 42L), clientId, clientSecretHash);
        setField(credential, "id", id);
        setField(credential, "createdAt", TIMESTAMP);
        setField(credential, "updatedAt", TIMESTAMP);
        return credential;
    }

    private void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}