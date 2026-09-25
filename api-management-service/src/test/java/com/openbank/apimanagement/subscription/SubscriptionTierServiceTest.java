package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.cache.CacheInvalidationService;
import com.openbank.apimanagement.exception.SubscriptionTierAlreadyExistsException;
import com.openbank.apimanagement.exception.SubscriptionTierNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionTierServiceTest {

    @Mock
    private SubscriptionTierRepository subscriptionTierRepository;

    @Mock
    private CacheInvalidationService cacheInvalidationService;

    @InjectMocks
    private SubscriptionTierService subscriptionTierService;

    @Test
    void createPersistsTierWithNameDescriptionPolicyAndTimestamps() {
        when(subscriptionTierRepository.save(any(SubscriptionTier.class))).thenAnswer(invocation -> {
            SubscriptionTier tier = invocation.getArgument(0);
            setField(tier, "id", 1L);
            return tier;
        });

        SubscriptionTier created = subscriptionTierService.create("Developer", "Standard access", 500, 30);

        ArgumentCaptor<SubscriptionTier> captor = ArgumentCaptor.forClass(SubscriptionTier.class);
        verify(subscriptionTierRepository).save(captor.capture());
        SubscriptionTier persisted = captor.getValue();
        assertThat(persisted.getName()).isEqualTo("Developer");
        assertThat(persisted.getDescription()).isEqualTo("Standard access");
        assertThat(persisted.getRequestsPerWindow()).isEqualTo(500);
        assertThat(persisted.getWindowSeconds()).isEqualTo(30);
        assertThat(persisted.getCreatedAt()).isEqualTo(persisted.getUpdatedAt());

        assertThat(created.getId()).isEqualTo(1L);
        assertThat(created.getName()).isEqualTo("Developer");
        assertThat(created.getDescription()).isEqualTo("Standard access");
        assertThat(created.getRequestsPerWindow()).isEqualTo(500);
        assertThat(created.getWindowSeconds()).isEqualTo(30);
    }

    @Test
    void createAllowsInternalWhitespaceInTheName() {
        when(subscriptionTierRepository.save(any(SubscriptionTier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SubscriptionTier created = subscriptionTierService.create("Developer Tier", "Standard access", 100, 60);

        assertThat(created.getName()).isEqualTo("Developer Tier");
    }

    @Test
    void createAllowsNullDescription() {
        when(subscriptionTierRepository.save(any(SubscriptionTier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SubscriptionTier created = subscriptionTierService.create("Developer", null, 100, 60);

        assertThat(created.getDescription()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void createRejectsBlankName(String name) {
        assertThatThrownBy(() -> subscriptionTierService.create(name, "Standard access", 100, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
        verify(subscriptionTierRepository, never()).save(any(SubscriptionTier.class));
    }

    @Test
    void createRejectsNullName() {
        assertThatThrownBy(() -> subscriptionTierService.create(null, "Standard access", 100, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
        verify(subscriptionTierRepository, never()).save(any(SubscriptionTier.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {" Developer", "Developer ", "\tDeveloper"})
    void createRejectsLeadingOrTrailingWhitespace(String name) {
        assertThatThrownBy(() -> subscriptionTierService.create(name, "Standard access", 100, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leading or trailing whitespace");
        verify(subscriptionTierRepository, never()).save(any(SubscriptionTier.class));
    }

    @Test
    void createRejectsNameLongerThan100Characters() {
        String name = "a".repeat(101);
        assertThatThrownBy(() -> subscriptionTierService.create(name, "Standard access", 100, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100")
                .hasMessageContaining("characters");
        verify(subscriptionTierRepository, never()).save(any(SubscriptionTier.class));
    }

    @Test
    void createRejectsDescriptionLongerThan500Characters() {
        String description = "a".repeat(501);
        assertThatThrownBy(() -> subscriptionTierService.create("Developer", description, 100, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("characters");
        verify(subscriptionTierRepository, never()).save(any(SubscriptionTier.class));
    }

    @Test
    void createRejectsDuplicateNameBeforePersisting() {
        when(subscriptionTierRepository.existsByName("Developer")).thenReturn(true);

        assertThatThrownBy(() -> subscriptionTierService.create("Developer", "Standard access", 100, 60))
                .isInstanceOf(SubscriptionTierAlreadyExistsException.class)
                .hasMessageContaining("Developer");
        verify(subscriptionTierRepository, never()).save(any(SubscriptionTier.class));
    }

    @Test
    void createTranslatesDatabaseUniqueViolationIntoAlreadyExists() {
        when(subscriptionTierRepository.existsByName("Developer")).thenReturn(false);
        when(subscriptionTierRepository.save(any(SubscriptionTier.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement; constraint"));

        assertThatThrownBy(() -> subscriptionTierService.create("Developer", "Standard access", 100, 60))
                .isInstanceOf(SubscriptionTierAlreadyExistsException.class)
                .hasMessageContaining("Developer");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    void createRejectsNonPositiveRequestsPerWindow(int requestsPerWindow) {
        assertThatThrownBy(() -> subscriptionTierService.create("Developer", "Standard access", requestsPerWindow, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestsPerWindow");
        verify(subscriptionTierRepository, never()).save(any(SubscriptionTier.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    void createRejectsNonPositiveWindowSeconds(int windowSeconds) {
        assertThatThrownBy(() -> subscriptionTierService.create("Developer", "Standard access", 100, windowSeconds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSeconds");
        verify(subscriptionTierRepository, never()).save(any(SubscriptionTier.class));
    }

    @Test
    void updateChangesSuppliedFieldsAndEvictsTheUpdatedTier() {
        SubscriptionTier stored = tier(7L, "Gold", "Old description", 100, 60);
        when(subscriptionTierRepository.findById(7L)).thenReturn(java.util.Optional.of(stored));
        when(subscriptionTierRepository.saveAndFlush(stored)).thenReturn(stored);

        SubscriptionTierResponse response = subscriptionTierService.update(
                7L, new UpdateSubscriptionTierRequest("Premium", "New description", 500, 30));

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.name()).isEqualTo("Premium");
        assertThat(response.description()).isEqualTo("New description");
        assertThat(response.requestsPerWindow()).isEqualTo(500);
        assertThat(response.windowSeconds()).isEqualTo(30);
        assertThat(response.createdAt()).isEqualTo(stored.getCreatedAt());
        assertThat(response.updatedAt()).isAfter(stored.getCreatedAt());
        verify(cacheInvalidationService).evict("subscriptionTier", 7L);
    }

    @Test
    void updateKeepsOmittedFields() {
        SubscriptionTier stored = tier(7L, "Gold", "Keep this", 100, 60);
        when(subscriptionTierRepository.findById(7L)).thenReturn(java.util.Optional.of(stored));
        when(subscriptionTierRepository.saveAndFlush(stored)).thenReturn(stored);

        SubscriptionTierResponse response = subscriptionTierService.update(
                7L, new UpdateSubscriptionTierRequest(null, null, 500, null));

        assertThat(response.name()).isEqualTo("Gold");
        assertThat(response.description()).isEqualTo("Keep this");
        assertThat(response.requestsPerWindow()).isEqualTo(500);
        assertThat(response.windowSeconds()).isEqualTo(60);
    }

    @Test
    void updateRejectsEmptyRequest() {
        assertThatThrownBy(() -> subscriptionTierService.update(
                7L, new UpdateSubscriptionTierRequest(null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one field");

        verify(subscriptionTierRepository, never()).findById(any());
        verify(cacheInvalidationService, never()).evict(any(), any());
    }

    @Test
    void updateAllowsTheExistingName() {
        SubscriptionTier stored = tier(7L, "Gold", "Description", 100, 60);
        when(subscriptionTierRepository.findById(7L)).thenReturn(java.util.Optional.of(stored));
        when(subscriptionTierRepository.saveAndFlush(stored)).thenReturn(stored);

        subscriptionTierService.update(7L, new UpdateSubscriptionTierRequest("Gold", null, null, null));

        verify(subscriptionTierRepository).existsByNameAndIdNot("Gold", 7L);
        verify(cacheInvalidationService).evict("subscriptionTier", 7L);
    }

    @Test
    void updateRejectsDuplicateNameBeforePersisting() {
        SubscriptionTier stored = tier(7L, "Gold", "Description", 100, 60);
        when(subscriptionTierRepository.findById(7L)).thenReturn(java.util.Optional.of(stored));
        when(subscriptionTierRepository.existsByNameAndIdNot("Silver", 7L)).thenReturn(true);

        assertThatThrownBy(() -> subscriptionTierService.update(
                7L, new UpdateSubscriptionTierRequest("Silver", null, null, null)))
                .isInstanceOf(SubscriptionTierAlreadyExistsException.class)
                .hasMessageContaining("Silver");

        verify(subscriptionTierRepository, never()).saveAndFlush(any(SubscriptionTier.class));
        verify(cacheInvalidationService, never()).evict(any(), any());
    }

    @Test
    void updateTranslatesDatabaseUniqueViolation() {
        SubscriptionTier stored = tier(7L, "Gold", "Description", 100, 60);
        when(subscriptionTierRepository.findById(7L)).thenReturn(java.util.Optional.of(stored));
        when(subscriptionTierRepository.saveAndFlush(stored))
                .thenThrow(new DataIntegrityViolationException("could not execute statement; constraint"));

        assertThatThrownBy(() -> subscriptionTierService.update(
                7L, new UpdateSubscriptionTierRequest("Silver", null, null, null)))
                .isInstanceOf(SubscriptionTierAlreadyExistsException.class)
                .hasMessageContaining("Silver");

        verify(cacheInvalidationService, never()).evict(any(), any());
    }

    @Test
    void updateThrowsNotFoundForUnknownTier() {
        when(subscriptionTierRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> subscriptionTierService.update(
                999L, new UpdateSubscriptionTierRequest("Gold", null, null, null)))
                .isInstanceOf(SubscriptionTierNotFoundException.class)
                .hasMessageContaining("999");

        verify(cacheInvalidationService, never()).evict(any(), any());
    }

    @Test
    void getReturnsTheStoredTierById() {
        SubscriptionTier expected = new SubscriptionTier("Gold", "Premium access", 1000, 60);
        setField(expected, "id", 7L);
        when(subscriptionTierRepository.findById(7L)).thenReturn(java.util.Optional.of(expected));

        SubscriptionTier found = subscriptionTierService.get(7L);

        assertThat(found).isSameAs(expected);
    }

    @Test
    void getThrowsNotFoundForUnknownTier() {
        when(subscriptionTierRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> subscriptionTierService.get(999L))
                .isInstanceOf(SubscriptionTierNotFoundException.class)
                .hasMessageContaining("999");
    }

    private SubscriptionTier tier(
            Long id,
            String name,
            String description,
            int requestsPerWindow,
            int windowSeconds) {
        SubscriptionTier tier = new SubscriptionTier(name, description, requestsPerWindow, windowSeconds);
        java.time.Instant timestamp = java.time.Instant.parse("2020-01-01T00:00:00Z");
        setField(tier, "id", id);
        setField(tier, "createdAt", timestamp);
        setField(tier, "updatedAt", timestamp);
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