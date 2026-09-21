package com.openbank.apimanagement.api;

import com.openbank.apimanagement.exception.ApiNotFoundException;
import com.openbank.apimanagement.exception.ApiVersionAlreadyExistsException;
import com.openbank.apimanagement.exception.ApiVersionNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
class ApiVersionServiceTest {

    @Mock
    private ApiVersionRepository apiVersionRepository;

    @Mock
    private ApiRepository apiRepository;

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
        assertThat(persisted.getCreatedAt()).isEqualTo(persisted.getUpdatedAt());

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.apiId()).isEqualTo(1L);
        assertThat(response.version()).isEqualTo("v1");
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
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

    private Api api(Long id) {
        Api api = new Api("Payments API", "Bank payment operations", "/payments");
        setField(api, "id", id);
        return api;
    }

    private ApiVersion version(Api api, Long id, String versionValue) {
        ApiVersion apiVersion = new ApiVersion(api, versionValue);
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