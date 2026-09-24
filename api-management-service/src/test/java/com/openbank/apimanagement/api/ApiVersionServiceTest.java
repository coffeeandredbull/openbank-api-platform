package com.openbank.apimanagement.api;

import com.openbank.apimanagement.cache.CacheInvalidationService;
import com.openbank.apimanagement.exception.ApiNotFoundException;
import com.openbank.apimanagement.exception.ApiVersionAlreadyExistsException;
import com.openbank.apimanagement.exception.ApiVersionNotFoundException;
import com.openbank.apimanagement.exception.InvalidLifecycleTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
class ApiVersionServiceTest {

    @Mock
    private ApiVersionRepository apiVersionRepository;

    @Mock
    private ApiRepository apiRepository;

    @Mock
    private CacheInvalidationService cacheInvalidationService;

    @InjectMocks
    private ApiVersionService apiVersionService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    @Test
    void createReturnsResponseWithApiIdAndTimestamps() {
        Api api = api(1L);
        when(apiRepository.findById(1L)).thenReturn(Optional.of(api));
        when(apiVersionRepository.existsByApiIdAndVersion(1L, "v1")).thenReturn(false);
        when(apiVersionRepository.save(any(ApiVersion.class))).thenAnswer(invocation -> {
            ApiVersion saved = invocation.getArgument(0);
            setField(saved, "id", 5L);
            return saved;
        });

        ApiVersionResponse response = apiVersionService.create(1L, new CreateApiVersionRequest("v1"));

        ArgumentCaptor<ApiVersion> captor = ArgumentCaptor.forClass(ApiVersion.class);
        verify(apiVersionRepository).save(captor.capture());
        ApiVersion persisted = captor.getValue();
        assertThat(persisted.getApi().getId()).isEqualTo(1L);
        assertThat(persisted.getVersion()).isEqualTo("v1");
        assertThat(persisted.getLifecycle()).isEqualTo(ApiVersionLifecycle.CREATED);
        assertThat(persisted.getCreatedAt()).isEqualTo(persisted.getUpdatedAt());

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.apiId()).isEqualTo(1L);
        assertThat(response.version()).isEqualTo("v1");
        assertThat(response.lifecycle()).isEqualTo(ApiVersionLifecycle.CREATED);
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();

        verify(cacheInvalidationService).evictAll("apiVersion");
    }

    @Test
    void createRejectsDuplicateVersionForSameApi() {
        Api api = api(1L);
        when(apiRepository.findById(1L)).thenReturn(Optional.of(api));
        when(apiVersionRepository.existsByApiIdAndVersion(1L, "v1")).thenReturn(true);

        CreateApiVersionRequest request = new CreateApiVersionRequest("v1");

        assertThatThrownBy(() -> apiVersionService.create(1L, request))
                .isInstanceOf(ApiVersionAlreadyExistsException.class)
                .hasMessageContaining("v1")
                .hasMessageContaining("1");
        verify(apiVersionRepository, never()).save(any(ApiVersion.class));
    }

    @Test
    void createTranslatesDatabaseUniqueViolationIntoConflict() {
        Api api = api(1L);
        when(apiRepository.findById(1L)).thenReturn(Optional.of(api));
        when(apiVersionRepository.existsByApiIdAndVersion(1L, "v1")).thenReturn(false);
        when(apiVersionRepository.save(any(ApiVersion.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        CreateApiVersionRequest request = new CreateApiVersionRequest("v1");

        assertThatThrownBy(() -> apiVersionService.create(1L, request))
                .isInstanceOf(ApiVersionAlreadyExistsException.class)
                .hasMessageContaining("v1")
                .hasMessageContaining("1");
    }

    @Test
    void createThrowsApiNotFoundWhenApiDoesNotExist() {
        when(apiRepository.findById(999L)).thenReturn(Optional.empty());

        CreateApiVersionRequest request = new CreateApiVersionRequest("v1");

        assertThatThrownBy(() -> apiVersionService.create(999L, request))
                .isInstanceOf(ApiNotFoundException.class)
                .hasMessageContaining("999");
        verify(apiVersionRepository, never()).save(any(ApiVersion.class));
    }

    @Test
    void sameVersionIsAllowedForDifferentApis() {
        Api first = api(1L);
        Api second = api(2L);
        when(apiRepository.findById(1L)).thenReturn(Optional.of(first));
        when(apiRepository.findById(2L)).thenReturn(Optional.of(second));
        when(apiVersionRepository.existsByApiIdAndVersion(1L, "v1")).thenReturn(true);
        when(apiVersionRepository.existsByApiIdAndVersion(2L, "v1")).thenReturn(false);
        when(apiVersionRepository.save(any(ApiVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> apiVersionService.create(1L, new CreateApiVersionRequest("v1")))
                .isInstanceOf(ApiVersionAlreadyExistsException.class);

        ApiVersionResponse response = apiVersionService.create(2L, new CreateApiVersionRequest("v1"));

        assertThat(response.apiId()).isEqualTo(2L);
        assertThat(response.version()).isEqualTo("v1");
    }

    @Test
    void getReturnsExistingVersionScopedToApi() {
        Api api = api(1L);
        when(apiRepository.existsById(1L)).thenReturn(true);
        ApiVersion stored = version(api, 5L, "v1");
        when(apiVersionRepository.findByApiIdAndId(1L, 5L)).thenReturn(Optional.of(stored));

        ApiVersionResponse response = apiVersionService.get(1L, 5L);

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.apiId()).isEqualTo(1L);
        assertThat(response.version()).isEqualTo("v1");
        assertThat(response.lifecycle()).isEqualTo(ApiVersionLifecycle.CREATED);
        assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
        assertThat(response.updatedAt()).isEqualTo(TIMESTAMP);
    }

    @Test
    void getThrowsApiNotFoundWhenApiDoesNotExist() {
        when(apiRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> apiVersionService.get(99L, 1L))
                .isInstanceOf(ApiNotFoundException.class)
                .hasMessageContaining("99");
        verify(apiVersionRepository, never()).findByApiIdAndId(any(), any());
    }

    @Test
    void getThrowsVersionNotFoundWhenVersionDoesNotExistForApi() {
        when(apiRepository.existsById(1L)).thenReturn(true);
        when(apiVersionRepository.findByApiIdAndId(1L, 404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiVersionService.get(1L, 404L))
                .isInstanceOf(ApiVersionNotFoundException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("1");
    }

    @Test
    void getThrowsVersionNotFoundWhenVersionBelongsToAnotherApi() {
        when(apiRepository.existsById(1L)).thenReturn(true);
        when(apiVersionRepository.findByApiIdAndId(1L, 5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiVersionService.get(1L, 5L))
                .isInstanceOf(ApiVersionNotFoundException.class)
                .hasMessageContaining("5");
    }

    @Test
    void listReturnsVersionsForApiInIdOrder() {
        Api api = api(1L);
        when(apiRepository.existsById(1L)).thenReturn(true);
        ApiVersion v1 = version(api, 1L, "v1");
        ApiVersion v2 = version(api, 2L, "v2");
        when(apiVersionRepository.findByApiIdOrderByIdAsc(1L)).thenReturn(List.of(v1, v2));

        List<ApiVersionResponse> responses = apiVersionService.list(1L);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo(1L);
        assertThat(responses.get(0).version()).isEqualTo("v1");
        assertThat(responses.get(1).id()).isEqualTo(2L);
        assertThat(responses.get(1).version()).isEqualTo("v2");
    }

    @Test
    void listThrowsApiNotFoundWhenApiDoesNotExist() {
        when(apiRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> apiVersionService.list(99L))
                .isInstanceOf(ApiNotFoundException.class)
                .hasMessageContaining("99");
        verify(apiVersionRepository, never()).findByApiIdOrderByIdAsc(any());
    }

    @ParameterizedTest
    @MethodSource("validTransitions")
    void changeLifecycleAllowsEachValidTransition(ApiVersionLifecycle from, ApiVersionLifecycle to) {
        Api api = api(1L);
        ApiVersion stored = version(api, 5L, "v1", from);
        when(apiRepository.existsById(1L)).thenReturn(true);
        when(apiVersionRepository.findByApiIdAndId(1L, 5L)).thenReturn(Optional.of(stored));
        when(apiVersionRepository.save(any(ApiVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApiVersionResponse response = apiVersionService.changeLifecycle(1L, 5L, new UpdateApiVersionLifecycleRequest(to));

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.apiId()).isEqualTo(1L);
        assertThat(response.version()).isEqualTo("v1");
        assertThat(response.lifecycle()).isEqualTo(to);
        assertThat(stored.getLifecycle()).isEqualTo(to);

        verify(cacheInvalidationService).evict("apiVersion", "1::5");
    }

    @ParameterizedTest
    @MethodSource("invalidTransitions")
    void changeLifecycleRejectsEveryInvalidTransition(ApiVersionLifecycle from, ApiVersionLifecycle to) {
        Api api = api(1L);
        ApiVersion stored = version(api, 5L, "v1", from);
        when(apiRepository.existsById(1L)).thenReturn(true);
        when(apiVersionRepository.findByApiIdAndId(1L, 5L)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> apiVersionService.changeLifecycle(1L, 5L, new UpdateApiVersionLifecycleRequest(to)))
                .isInstanceOf(InvalidLifecycleTransitionException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
        assertThat(stored.getLifecycle()).isEqualTo(from);
        verify(apiVersionRepository, never()).save(any(ApiVersion.class));
    }

    @ParameterizedTest
    @MethodSource("sameStateTransitions")
    void changeLifecycleRejectsSameStateTransition(ApiVersionLifecycle state) {
        Api api = api(1L);
        ApiVersion stored = version(api, 5L, "v1", state);
        when(apiRepository.existsById(1L)).thenReturn(true);
        when(apiVersionRepository.findByApiIdAndId(1L, 5L)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> apiVersionService.changeLifecycle(1L, 5L, new UpdateApiVersionLifecycleRequest(state)))
                .isInstanceOf(InvalidLifecycleTransitionException.class);
        assertThat(stored.getLifecycle()).isEqualTo(state);
        verify(apiVersionRepository, never()).save(any(ApiVersion.class));
    }

    @Test
    void changeLifecycleUpdatesUpdatedAtButNotCreatedAt() {
        Api api = api(1L);
        Instant oldUpdatedAt = TIMESTAMP;
        ApiVersion stored = new ApiVersion(api, "v1");
        setField(stored, "id", 5L);
        setField(stored, "lifecycle", ApiVersionLifecycle.CREATED);
        setField(stored, "createdAt", TIMESTAMP);
        setField(stored, "updatedAt", oldUpdatedAt);
        when(apiRepository.existsById(1L)).thenReturn(true);
        when(apiVersionRepository.findByApiIdAndId(1L, 5L)).thenReturn(Optional.of(stored));
        when(apiVersionRepository.save(any(ApiVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApiVersionResponse response = apiVersionService.changeLifecycle(
                1L, 5L, new UpdateApiVersionLifecycleRequest(ApiVersionLifecycle.PUBLISHED));

        assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
        assertThat(stored.getCreatedAt()).isEqualTo(TIMESTAMP);
        assertThat(response.updatedAt()).isAfter(oldUpdatedAt);
        assertThat(stored.getUpdatedAt()).isAfter(oldUpdatedAt);
    }

    @Test
    void changeLifecycleThrowsApiNotFoundWhenApiDoesNotExist() {
        when(apiRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> apiVersionService.changeLifecycle(
                99L, 1L, new UpdateApiVersionLifecycleRequest(ApiVersionLifecycle.PUBLISHED)))
                .isInstanceOf(ApiNotFoundException.class)
                .hasMessageContaining("99");
        verify(apiVersionRepository, never()).findByApiIdAndId(any(), any());
    }

    @Test
    void changeLifecycleThrowsVersionNotFoundWhenVersionDoesNotExistForApi() {
        when(apiRepository.existsById(1L)).thenReturn(true);
        when(apiVersionRepository.findByApiIdAndId(1L, 404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiVersionService.changeLifecycle(
                1L, 404L, new UpdateApiVersionLifecycleRequest(ApiVersionLifecycle.PUBLISHED)))
                .isInstanceOf(ApiVersionNotFoundException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("1");
        verify(apiVersionRepository, never()).save(any(ApiVersion.class));
    }

    @Test
    void changeLifecycleThrowsVersionNotFoundWhenVersionBelongsToAnotherApi() {
        when(apiRepository.existsById(1L)).thenReturn(true);
        when(apiVersionRepository.findByApiIdAndId(1L, 5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiVersionService.changeLifecycle(
                1L, 5L, new UpdateApiVersionLifecycleRequest(ApiVersionLifecycle.PUBLISHED)))
                .isInstanceOf(ApiVersionNotFoundException.class)
                .hasMessageContaining("5");
    }

    private static Stream<Arguments> validTransitions() {
        return Stream.of(
                Arguments.of(ApiVersionLifecycle.CREATED, ApiVersionLifecycle.PUBLISHED),
                Arguments.of(ApiVersionLifecycle.PUBLISHED, ApiVersionLifecycle.DEPRECATED),
                Arguments.of(ApiVersionLifecycle.DEPRECATED, ApiVersionLifecycle.RETIRED),
                Arguments.of(ApiVersionLifecycle.CREATED, ApiVersionLifecycle.RETIRED)
        );
    }

    private static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                Arguments.of(ApiVersionLifecycle.PUBLISHED, ApiVersionLifecycle.CREATED),
                Arguments.of(ApiVersionLifecycle.PUBLISHED, ApiVersionLifecycle.RETIRED),
                Arguments.of(ApiVersionLifecycle.DEPRECATED, ApiVersionLifecycle.CREATED),
                Arguments.of(ApiVersionLifecycle.DEPRECATED, ApiVersionLifecycle.PUBLISHED),
                Arguments.of(ApiVersionLifecycle.RETIRED, ApiVersionLifecycle.CREATED),
                Arguments.of(ApiVersionLifecycle.RETIRED, ApiVersionLifecycle.PUBLISHED),
                Arguments.of(ApiVersionLifecycle.RETIRED, ApiVersionLifecycle.DEPRECATED),
                Arguments.of(ApiVersionLifecycle.RETIRED, ApiVersionLifecycle.RETIRED)
        );
    }

    private static Stream<Arguments> sameStateTransitions() {
        return Stream.of(
                ApiVersionLifecycle.CREATED,
                ApiVersionLifecycle.PUBLISHED,
                ApiVersionLifecycle.DEPRECATED
        ).map(Arguments::of);
    }

    private Api api(Long id) {
        Api api = new Api("Payments API", "Bank payment operations", "/payments");
        setField(api, "id", id);
        return api;
    }

    private ApiVersion version(Api api, Long id, String versionValue) {
        return version(api, id, versionValue, ApiVersionLifecycle.CREATED);
    }

    private ApiVersion version(Api api, Long id, String versionValue, ApiVersionLifecycle lifecycle) {
        ApiVersion apiVersion = new ApiVersion(api, versionValue);
        setField(apiVersion, "lifecycle", lifecycle);
        setField(apiVersion, "id", id);
        setField(apiVersion, "createdAt", TIMESTAMP);
        setField(apiVersion, "updatedAt", TIMESTAMP);
        return apiVersion;
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