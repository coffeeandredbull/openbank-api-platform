package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.api.Api;
import com.openbank.apimanagement.api.ApiVersion;
import com.openbank.apimanagement.api.ApiVersionLifecycle;
import com.openbank.apimanagement.api.ApiVersionRepository;
import com.openbank.apimanagement.application.Application;
import com.openbank.apimanagement.application.ApplicationRepository;
import com.openbank.apimanagement.exception.ApiVersionNotFoundException;
import com.openbank.apimanagement.exception.ApplicationNotFoundException;
import com.openbank.apimanagement.exception.InvalidSubscriptionStatusTransitionException;
import com.openbank.apimanagement.exception.SubscriptionAlreadyExistsException;
import com.openbank.apimanagement.exception.SubscriptionNotFoundException;
import com.openbank.apimanagement.exception.SubscriptionTierNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private ApiVersionRepository apiVersionRepository;

    @Mock
    private com.openbank.apimanagement.api.ApiRepository apiRepository;

    @Mock
    private SubscriptionTierRepository subscriptionTierRepository;

    @InjectMocks
    private SubscriptionService subscriptionService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    @Test
    void createSubscribesOwnedApplicationToApiVersionAndStoresTimestamps() {
        Application application = application(10L, 42L);
        ApiVersion apiVersion = apiVersion(20L);
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L)).thenReturn(Optional.of(application));
        when(apiVersionRepository.findById(20L)).thenReturn(Optional.of(apiVersion));
        when(subscriptionTierRepository.findById(15L)).thenReturn(Optional.of(tier(15L, "Developer")));
        when(subscriptionRepository.existsByApplicationIdAndApiVersionId(10L, 20L)).thenReturn(false);
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(invocation -> {
            Subscription saved = invocation.getArgument(0);
            setField(saved, "id", 30L);
            return saved;
        });

        SubscriptionResponse response = subscriptionService.create(
                42L, new CreateSubscriptionRequest(10L, 20L, 15L));

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        Subscription persisted = captor.getValue();
        assertThat(persisted.getApplication().getId()).isEqualTo(10L);
        assertThat(persisted.getApiVersion().getId()).isEqualTo(20L);
        assertThat(persisted.getTier().getId()).isEqualTo(15L);
        assertThat(persisted.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(persisted.getRevokedAt()).isNull();
        assertThat(persisted.getCreatedAt()).isEqualTo(persisted.getUpdatedAt());

        assertThat(response.id()).isEqualTo(30L);
        assertThat(response.applicationId()).isEqualTo(10L);
        assertThat(response.apiVersionId()).isEqualTo(20L);
        assertThat(response.tierId()).isEqualTo(15L);
        assertThat(response.tierName()).isEqualTo("Developer");
        assertThat(response.status()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(response.revokedAt()).isNull();
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    void createRejectsMissingTier() {
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L))
                .thenReturn(Optional.of(application(10L, 42L)));
        when(apiVersionRepository.findById(20L)).thenReturn(Optional.of(apiVersion(20L)));
        when(subscriptionTierRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.create(42L, new CreateSubscriptionRequest(10L, 20L, 999L)))
                .isInstanceOf(SubscriptionTierNotFoundException.class)
                .hasMessageContaining("999");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    void createRejectsDuplicateSubscription() {
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L))
                .thenReturn(Optional.of(application(10L, 42L)));
        when(apiVersionRepository.findById(20L)).thenReturn(Optional.of(apiVersion(20L)));
        when(subscriptionTierRepository.findById(15L)).thenReturn(Optional.of(tier(15L, "Developer")));
        when(subscriptionRepository.existsByApplicationIdAndApiVersionId(10L, 20L)).thenReturn(true);

        assertThatThrownBy(() -> subscriptionService.create(42L, new CreateSubscriptionRequest(10L, 20L, 15L)))
                .isInstanceOf(SubscriptionAlreadyExistsException.class)
                .hasMessageContaining("10")
                .hasMessageContaining("20");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    void createRejectsMissingApplication() {
        when(applicationRepository.findByIdAndOwnerUserId(999L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.create(42L, new CreateSubscriptionRequest(999L, 20L, 15L)))
                .isInstanceOf(ApplicationNotFoundException.class)
                .hasMessageContaining("999");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    void createRejectsApplicationOwnedByAnotherUser() {
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.create(42L, new CreateSubscriptionRequest(10L, 20L, 15L)))
                .isInstanceOf(ApplicationNotFoundException.class)
                .hasMessageContaining("10");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    void createRejectsMissingApiVersion() {
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L))
                .thenReturn(Optional.of(application(10L, 42L)));
        when(apiVersionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.create(42L, new CreateSubscriptionRequest(10L, 999L, 15L)))
                .isInstanceOf(ApiVersionNotFoundException.class)
                .hasMessageContaining("999");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    void createTranslatesDatabaseConstraintRaceIntoDuplicateSubscription() {
        when(applicationRepository.findByIdAndOwnerUserId(10L, 42L))
                .thenReturn(Optional.of(application(10L, 42L)));
        when(apiVersionRepository.findById(20L)).thenReturn(Optional.of(apiVersion(20L)));
        when(subscriptionTierRepository.findById(15L)).thenReturn(Optional.of(tier(15L, "Developer")));
        when(subscriptionRepository.existsByApplicationIdAndApiVersionId(10L, 20L)).thenReturn(false);
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement; constraint"));

        assertThatThrownBy(() -> subscriptionService.create(42L, new CreateSubscriptionRequest(10L, 20L, 15L)))
                .isInstanceOf(SubscriptionAlreadyExistsException.class)
                .hasMessageContaining("10")
                .hasMessageContaining("20");
    }

    @Test
    void getReturnsSubscriptionOwnedByCurrentUser() {
        Subscription stored = subscription(30L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        when(subscriptionRepository.findByIdAndApplication_OwnerUserId(30L, 42L))
                .thenReturn(Optional.of(stored));

        SubscriptionResponse response = subscriptionService.get(30L, 42L);

        assertThat(response.id()).isEqualTo(30L);
        assertThat(response.applicationId()).isEqualTo(10L);
        assertThat(response.apiVersionId()).isEqualTo(20L);
        assertThat(response.tierId()).isEqualTo(15L);
        assertThat(response.tierName()).isEqualTo("Developer");
        assertThat(response.status()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(response.revokedAt()).isNull();
        assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
        assertThat(response.updatedAt()).isEqualTo(TIMESTAMP);
    }

    @Test
    void getThrowsNotFoundWhenSubscriptionBelongsToApplicationOfAnotherUser() {
        when(subscriptionRepository.findByIdAndApplication_OwnerUserId(30L, 7L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.get(30L, 7L))
                .isInstanceOf(SubscriptionNotFoundException.class)
                .hasMessageContaining("30");
    }

    @Test
    void listReturnsOnlySubscriptionsForApplicationsOwnedByCurrentUser() {
        Subscription first = subscription(1L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        Subscription second = subscription(2L, 10L, 21L, TIMESTAMP, TIMESTAMP);
        when(subscriptionRepository.findByApplication_OwnerUserIdOrderByIdAsc(42L))
                .thenReturn(List.of(first, second));

        List<SubscriptionResponse> responses = subscriptionService.list(42L);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo(1L);
        assertThat(responses.get(0).applicationId()).isEqualTo(10L);
        assertThat(responses.get(1).id()).isEqualTo(2L);
        verify(subscriptionRepository).findByApplication_OwnerUserIdOrderByIdAsc(42L);
    }

    @Test
    void listPreservesDeterministicOrderingBySubscriptionId() {
        Subscription older = subscription(4L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        Subscription newer = subscription(9L, 10L, 21L, TIMESTAMP, TIMESTAMP);
        when(subscriptionRepository.findByApplication_OwnerUserIdOrderByIdAsc(42L))
                .thenReturn(List.of(older, newer));

        List<SubscriptionResponse> responses = subscriptionService.list(42L);

        assertThat(responses.stream().map(SubscriptionResponse::id).toList())
                .containsExactly(4L, 9L);
    }

    @ParameterizedTest
    @EnumSource(value = ApiVersionLifecycle.class)
    void findActivePolicyIgnoresTheApiVersionLifecycleState(ApiVersionLifecycle lifecycle) {
        Subscription stored = subscription(30L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        setField(stored, "status", SubscriptionStatus.ACTIVE);
        when(apiRepository.findByContextPath("/payments")).thenReturn(Optional.of(api(1L)));
        when(apiVersionRepository.findByApiIdAndVersion(1L, "v1"))
                .thenReturn(Optional.of(apiVersion(20L, lifecycle)));
        when(subscriptionRepository.findByApiVersionIdAndApplication_OwnerUserIdAndStatus(
                20L, 42L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(stored));

        SubscriptionPolicy policy = subscriptionService.findActivePolicy(42L, "/payments", "v1");

        assertThat(policy).isNotNull();
        assertThat(policy.tierId()).isEqualTo(15L);
        assertThat(policy.tierName()).isEqualTo("Developer");
        assertThat(policy.requestsPerWindow()).isEqualTo(100);
        assertThat(policy.windowSeconds()).isEqualTo(60);
    }

    @Test
    void findActivePolicyReturnsTheTierPolicyOfTheActiveSubscription() {
        Subscription stored = subscription(30L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        setField(stored, "status", SubscriptionStatus.ACTIVE);
        when(apiRepository.findByContextPath("/payments")).thenReturn(Optional.of(api(1L)));
        when(apiVersionRepository.findByApiIdAndVersion(1L, "v1"))
                .thenReturn(Optional.of(apiVersion(20L)));
        when(subscriptionRepository.findByApiVersionIdAndApplication_OwnerUserIdAndStatus(
                20L, 42L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(stored));

        SubscriptionPolicy policy = subscriptionService.findActivePolicy(42L, "/payments", "v1");

        assertThat(policy.tierId()).isEqualTo(15L);
        assertThat(policy.tierName()).isEqualTo("Developer");
        assertThat(policy.requestsPerWindow()).isEqualTo(100);
        assertThat(policy.windowSeconds()).isEqualTo(60);
    }

    @Test
    void findActivePolicyReturnsNullWhenTheOnlySubscriptionIsNotActive() {
        Subscription stored = subscription(30L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        setField(stored, "status", SubscriptionStatus.PENDING);
        when(apiRepository.findByContextPath("/payments")).thenReturn(Optional.of(api(1L)));
        when(apiVersionRepository.findByApiIdAndVersion(1L, "v1"))
                .thenReturn(Optional.of(apiVersion(20L)));
        when(subscriptionRepository.findByApiVersionIdAndApplication_OwnerUserIdAndStatus(
                20L, 42L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(stored));

        assertThat(subscriptionService.findActivePolicy(42L, "/payments", "v1")).isNull();
    }

    @Test
    void findActivePolicyByApplicationReturnsTheTierPolicyOfTheActiveSubscription() {
        Subscription stored = subscription(30L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        setField(stored, "status", SubscriptionStatus.ACTIVE);
        when(apiRepository.findByContextPath("/payments")).thenReturn(Optional.of(api(1L)));
        when(apiVersionRepository.findByApiIdAndVersion(1L, "v1"))
                .thenReturn(Optional.of(apiVersion(20L)));
        when(subscriptionRepository.findByApiVersionIdAndApplicationIdAndStatus(
                20L, 55L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(stored));

        SubscriptionPolicy policy = subscriptionService.findActivePolicyByApplication(55L, "/payments", "v1");

        assertThat(policy).isNotNull();
        assertThat(policy.tierId()).isEqualTo(15L);
        assertThat(policy.tierName()).isEqualTo("Developer");
        assertThat(policy.requestsPerWindow()).isEqualTo(100);
        assertThat(policy.windowSeconds()).isEqualTo(60);
    }

    @Test
    void findActivePolicyByApplicationReturnsNullWhenTheOnlySubscriptionIsNotActive() {
        Subscription stored = subscription(30L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        setField(stored, "status", SubscriptionStatus.DENIED);
        when(apiRepository.findByContextPath("/payments")).thenReturn(Optional.of(api(1L)));
        when(apiVersionRepository.findByApiIdAndVersion(1L, "v1"))
                .thenReturn(Optional.of(apiVersion(20L)));
        when(subscriptionRepository.findByApiVersionIdAndApplicationIdAndStatus(
                20L, 55L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(stored));

        assertThat(subscriptionService.findActivePolicyByApplication(55L, "/payments", "v1")).isNull();
    }

    @Test
    void findActivePolicyReturnsNullWhenThereIsNoMatchingApi() {
        when(apiRepository.findByContextPath("/no-such-api")).thenReturn(Optional.empty());

        assertThat(subscriptionService.findActivePolicy(42L, "/no-such-api", "v1")).isNull();
        verify(subscriptionRepository, never())
                .findByApiVersionIdAndApplication_OwnerUserIdAndStatus(any(), any(), any());
    }

    @Test
    void findActivePolicyReturnsNullWhenTheApiVersionDoesNotExist() {
        when(apiRepository.findByContextPath("/payments")).thenReturn(Optional.of(api(1L)));
        when(apiVersionRepository.findByApiIdAndVersion(1L, "v9")).thenReturn(Optional.empty());

        assertThat(subscriptionService.findActivePolicy(42L, "/payments", "v9")).isNull();
        verify(subscriptionRepository, never())
                .findByApiVersionIdAndApplication_OwnerUserIdAndStatus(any(), any(), any());
    }

    @Test
    void findActivePolicyReturnsNullWhenTheUserHoldsNoSubscriptionForTheApiVersion() {
        when(apiRepository.findByContextPath("/payments")).thenReturn(Optional.of(api(1L)));
        when(apiVersionRepository.findByApiIdAndVersion(1L, "v1"))
                .thenReturn(Optional.of(apiVersion(20L)));
        when(subscriptionRepository.findByApiVersionIdAndApplication_OwnerUserIdAndStatus(
                20L, 42L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(subscriptionService.findActivePolicy(42L, "/payments", "v1")).isNull();
    }

    @ParameterizedTest
    @MethodSource("allowedStatusTransitions")
    void changeStatusPerformsAllowedTransitions(SubscriptionStatus from, SubscriptionStatus to) {
        Subscription stored = subscription(30L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        setField(stored, "status", from);
        when(subscriptionRepository.findById(30L)).thenReturn(Optional.of(stored));
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SubscriptionResponse response = subscriptionService.changeStatus(
                30L, new UpdateSubscriptionStatusRequest(to));

        assertThat(response.id()).isEqualTo(30L);
        assertThat(response.status()).isEqualTo(to);
        if (to == SubscriptionStatus.REVOKED) {
            assertThat(response.revokedAt()).isNotNull();
        } else {
            assertThat(response.revokedAt()).isNull();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidStatusTransitions")
    void changeStatusRejectsInvalidTransitions(SubscriptionStatus from, SubscriptionStatus to) {
        Subscription stored = subscription(30L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        setField(stored, "status", from);
        when(subscriptionRepository.findById(30L)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> subscriptionService.changeStatus(
                30L, new UpdateSubscriptionStatusRequest(to)))
                .isInstanceOf(InvalidSubscriptionStatusTransitionException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    void changeStatusPersistsTheUpdatedFields() {
        Subscription stored = subscription(30L, 10L, 20L, TIMESTAMP, TIMESTAMP);
        setField(stored, "status", SubscriptionStatus.PENDING);
        when(subscriptionRepository.findById(30L)).thenReturn(Optional.of(stored));
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        subscriptionService.changeStatus(30L, new UpdateSubscriptionStatusRequest(SubscriptionStatus.REVOKED));

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.REVOKED);
        assertThat(captor.getValue().getRevokedAt()).isNotNull();
        assertThat(captor.getValue().getUpdatedAt()).isAfter(TIMESTAMP);
    }

    @Test
    void changeStatusThrowsNotFoundWhenSubscriptionDoesNotExist() {
        when(subscriptionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.changeStatus(
                999L, new UpdateSubscriptionStatusRequest(SubscriptionStatus.ACTIVE)))
                .isInstanceOf(SubscriptionNotFoundException.class)
                .hasMessageContaining("999");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    private static Stream<Arguments> allowedStatusTransitions() {
        return Stream.of(
                Arguments.of(SubscriptionStatus.PENDING, SubscriptionStatus.ACTIVE),
                Arguments.of(SubscriptionStatus.PENDING, SubscriptionStatus.DENIED),
                Arguments.of(SubscriptionStatus.PENDING, SubscriptionStatus.REVOKED),
                Arguments.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.REVOKED),
                Arguments.of(SubscriptionStatus.DENIED, SubscriptionStatus.ACTIVE),
                Arguments.of(SubscriptionStatus.DENIED, SubscriptionStatus.REVOKED));
    }

    private static Stream<Arguments> invalidStatusTransitions() {
        return Stream.of(
                Arguments.of(SubscriptionStatus.PENDING, SubscriptionStatus.PENDING),
                Arguments.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                Arguments.of(SubscriptionStatus.DENIED, SubscriptionStatus.DENIED),
                Arguments.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PENDING),
                Arguments.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.DENIED),
                Arguments.of(SubscriptionStatus.DENIED, SubscriptionStatus.PENDING),
                Arguments.of(SubscriptionStatus.REVOKED, SubscriptionStatus.PENDING),
                Arguments.of(SubscriptionStatus.REVOKED, SubscriptionStatus.ACTIVE),
                Arguments.of(SubscriptionStatus.REVOKED, SubscriptionStatus.DENIED),
                Arguments.of(SubscriptionStatus.REVOKED, SubscriptionStatus.REVOKED));
    }

    private Api api(Long id) {
        Api api = new Api("Payments API", "Payment operations", "/payments");
        setField(api, "id", id);
        return api;
    }

    private Application application(Long id, Long ownerUserId) {
        Application application = new Application("My App", "Demo", ownerUserId);
        setField(application, "id", id);
        setField(application, "createdAt", TIMESTAMP);
        setField(application, "updatedAt", TIMESTAMP);
        return application;
    }

    private ApiVersion apiVersion(Long id) {
        ApiVersion apiVersion = new ApiVersion(new Api("Payments", "Payment API", "/payments"), "v1");
        setField(apiVersion, "id", id);
        setField(apiVersion, "createdAt", TIMESTAMP);
        setField(apiVersion, "updatedAt", TIMESTAMP);
        return apiVersion;
    }

    private ApiVersion apiVersion(Long id, ApiVersionLifecycle lifecycle) {
        ApiVersion apiVersion = apiVersion(id);
        setField(apiVersion, "lifecycle", lifecycle);
        return apiVersion;
    }

    private Subscription subscription(Long id, Long applicationId, Long apiVersionId, Instant createdAt, Instant updatedAt) {
        Subscription subscription = new Subscription(
                application(applicationId, 42L), apiVersion(apiVersionId), tier(15L, "Developer"));
        setField(subscription, "id", id);
        setField(subscription, "createdAt", createdAt);
        setField(subscription, "updatedAt", updatedAt);
        return subscription;
    }

    private SubscriptionTier tier(Long id, String name) {
        SubscriptionTier tier = new SubscriptionTier(name, "Standard access", 100, 60);
        setField(tier, "id", id);
        setField(tier, "createdAt", TIMESTAMP);
        setField(tier, "updatedAt", TIMESTAMP);
        return tier;
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