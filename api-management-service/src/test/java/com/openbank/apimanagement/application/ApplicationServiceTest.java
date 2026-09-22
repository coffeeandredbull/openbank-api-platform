package com.openbank.apimanagement.application;

import com.openbank.apimanagement.exception.ApplicationNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @InjectMocks
    private ApplicationService applicationService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    @Test
    void createStoresProvidedNameDescriptionAndOwner() {
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> {
            Application saved = invocation.getArgument(0);
            setField(saved, "id", 5L);
            return saved;
        });

        ApplicationResponse response = applicationService.create(
                42L, new CreateApplicationRequest("My Demo Application", "Application used for API testing"));

        ArgumentCaptor<Application> captor = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository).save(captor.capture());
        Application persisted = captor.getValue();
        assertThat(persisted.getName()).isEqualTo("My Demo Application");
        assertThat(persisted.getDescription()).isEqualTo("Application used for API testing");
        assertThat(persisted.getOwnerUserId()).isEqualTo(42L);
        assertThat(persisted.getCreatedAt()).isEqualTo(persisted.getUpdatedAt());

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.name()).isEqualTo("My Demo Application");
        assertThat(response.description()).isEqualTo("Application used for API testing");
        assertThat(response.ownerUserId()).isEqualTo(42L);
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    void createAllowsMissingDescription() {
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> {
            Application saved = invocation.getArgument(0);
            setField(saved, "id", 5L);
            return saved;
        });

        ApplicationResponse response = applicationService.create(42L, new CreateApplicationRequest("My App", null));

        assertThat(response.name()).isEqualTo("My App");
        assertThat(response.description()).isNull();
        assertThat(response.ownerUserId()).isEqualTo(42L);
    }

    @Test
    void getReturnsOwnApplication() {
        Application stored = application(5L, 42L, "My App", "Demo", TIMESTAMP, TIMESTAMP);
        when(applicationRepository.findByIdAndOwnerUserId(5L, 42L)).thenReturn(Optional.of(stored));

        ApplicationResponse response = applicationService.get(5L, 42L);

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.name()).isEqualTo("My App");
        assertThat(response.ownerUserId()).isEqualTo(42L);
        assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
    }

    @Test
    void getThrowsNotFoundWhenApplicationBelongsToAnotherUser() {
        when(applicationRepository.findByIdAndOwnerUserId(5L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applicationService.get(5L, 7L))
                .isInstanceOf(ApplicationNotFoundException.class)
                .hasMessageContaining("5");
    }

    @Test
    void listReturnsOnlyOwnersApplications() {
        Application first = application(1L, 42L, "App One", "Demo", TIMESTAMP, TIMESTAMP);
        Application second = application(2L, 42L, "App Two", "Demo", TIMESTAMP, TIMESTAMP);
        when(applicationRepository.findByOwnerUserIdOrderByIdAsc(42L)).thenReturn(List.of(first, second));

        List<ApplicationResponse> responses = applicationService.list(42L);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo(1L);
        assertThat(responses.get(0).name()).isEqualTo("App One");
        assertThat(responses.get(1).id()).isEqualTo(2L);
        assertThat(responses.get(1).name()).isEqualTo("App Two");
        verify(applicationRepository).findByOwnerUserIdOrderByIdAsc(42L);
    }

    @Test
    void updateOwnApplicationChangesFieldsAndTimestamps() {
        Application stored = application(5L, 42L, "My App", "Old description", TIMESTAMP, TIMESTAMP);
        when(applicationRepository.findByIdAndOwnerUserId(5L, 42L)).thenReturn(Optional.of(stored));
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationResponse response = applicationService.update(
                5L, 42L, new UpdateApplicationRequest("New Name", "New description"));

        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.description()).isEqualTo("New description");
        assertThat(response.ownerUserId()).isEqualTo(42L);
        assertThat(stored.getCreatedAt()).isEqualTo(TIMESTAMP);
        assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
        assertThat(stored.getUpdatedAt()).isAfter(TIMESTAMP);
        assertThat(response.updatedAt()).isAfter(TIMESTAMP);
    }

    @Test
    void updateKeepsFieldsThatAreOmitted() {
        Application stored = application(5L, 42L, "My App", "Keep me", TIMESTAMP, TIMESTAMP);
        when(applicationRepository.findByIdAndOwnerUserId(5L, 42L)).thenReturn(Optional.of(stored));
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationResponse response = applicationService.update(5L, 42L, new UpdateApplicationRequest("New Name", null));

        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.description()).isEqualTo("Keep me");
        assertThat(response.updatedAt()).isAfter(TIMESTAMP);
    }

    @Test
    void updateWithNoFieldsReturnsCurrentStateWithoutSaving() {
        Application stored = application(5L, 42L, "My App", "Demo", TIMESTAMP, TIMESTAMP);
        when(applicationRepository.findByIdAndOwnerUserId(5L, 42L)).thenReturn(Optional.of(stored));

        ApplicationResponse response = applicationService.update(5L, 42L, new UpdateApplicationRequest(null, null));

        assertThat(response.name()).isEqualTo("My App");
        assertThat(response.description()).isEqualTo("Demo");
        assertThat(response.updatedAt()).isEqualTo(TIMESTAMP);
        verify(applicationRepository, never()).save(any(Application.class));
    }

    @Test
    void updateThrowsNotFoundWhenApplicationBelongsToAnotherUser() {
        when(applicationRepository.findByIdAndOwnerUserId(5L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applicationService.update(5L, 7L, new UpdateApplicationRequest("New Name", null)))
                .isInstanceOf(ApplicationNotFoundException.class)
                .hasMessageContaining("5");
        verify(applicationRepository, never()).save(any(Application.class));
    }

    private Application application(Long id, Long ownerUserId, String name, String description, Instant createdAt, Instant updatedAt) {
        Application application = new Application(name, description, ownerUserId);
        setField(application, "id", id);
        setField(application, "createdAt", createdAt);
        setField(application, "updatedAt", updatedAt);
        return application;
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