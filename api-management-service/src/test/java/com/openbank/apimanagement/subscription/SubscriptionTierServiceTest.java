package com.openbank.apimanagement.subscription;

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