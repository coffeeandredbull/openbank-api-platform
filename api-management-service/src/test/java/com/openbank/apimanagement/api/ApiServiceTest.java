package com.openbank.apimanagement.api;

import com.openbank.apimanagement.exception.ApiNotFoundException;
import com.openbank.apimanagement.exception.ContextPathAlreadyExistsException;
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
class ApiServiceTest {

    @Mock
    private ApiRepository apiRepository;

    @InjectMocks
    private ApiService apiService;

    @Test
    void createReturnsResponseWithGeneratedIdAndTimestamps() {
        Api saved = new Api("Payments API", "Bank payment operations", "/payments");
        setId(saved, 1L);
        setField(saved, "createdAt", Instant.parse("2026-09-21T10:00:00Z"));
        setField(saved, "updatedAt", Instant.parse("2026-09-21T10:00:00Z"));
        when(apiRepository.existsByContextPath("/payments")).thenReturn(false);
        when(apiRepository.save(any(Api.class))).thenReturn(saved);

        ApiResponse response = apiService.create(new CreateApiRequest("Payments API", "Bank payment operations", "/payments"));

        ArgumentCaptor<Api> captor = ArgumentCaptor.forClass(Api.class);
        verify(apiRepository).save(captor.capture());
        Api persisted = captor.getValue();
        assertThat(persisted.getName()).isEqualTo("Payments API");
        assertThat(persisted.getDescription()).isEqualTo("Bank payment operations");
        assertThat(persisted.getContextPath()).isEqualTo("/payments");
        assertThat(persisted.getCreatedAt()).isEqualTo(persisted.getUpdatedAt());

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Payments API");
        assertThat(response.description()).isEqualTo("Bank payment operations");
        assertThat(response.contextPath()).isEqualTo("/payments");
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-09-21T10:00:00Z"));
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-09-21T10:00:00Z"));
    }

    @Test
    void createRejectsDuplicateContextPath() {
        when(apiRepository.existsByContextPath("/payments")).thenReturn(true);

        CreateApiRequest request = new CreateApiRequest("Payments API", "Bank payment operations", "/payments");

        assertThatThrownBy(() -> apiService.create(request))
                .isInstanceOf(ContextPathAlreadyExistsException.class);
        verify(apiRepository, never()).save(any(Api.class));
    }

    @Test
    void createTranslatesDatabaseUniqueViolationIntoConflict() {
        when(apiRepository.existsByContextPath("/payments")).thenReturn(false);
        when(apiRepository.save(any(Api.class))).thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        CreateApiRequest request = new CreateApiRequest("Payments API", "Bank payment operations", "/payments");

        assertThatThrownBy(() -> apiService.create(request))
                .isInstanceOf(ContextPathAlreadyExistsException.class)
                .hasMessageContaining("/payments");
    }

    @Test
    void getReturnsExistingApi() {
        Api existing = new Api("Accounts API", "Bank account operations", "/accounts");
        setId(existing, 7L);
        when(apiRepository.findById(7L)).thenReturn(Optional.of(existing));

        ApiResponse response = apiService.get(7L);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.name()).isEqualTo("Accounts API");
        assertThat(response.contextPath()).isEqualTo("/accounts");
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    void getThrowsWhenApiDoesNotExist() {
        when(apiRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiService.get(99L))
                .isInstanceOf(ApiNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void listReturnsAllApisInDeterministicOrder() {
        Api first = new Api("Payments API", "Bank payment operations", "/payments");
        setId(first, 1L);
        Api second = new Api("Accounts API", "Bank account operations", "/accounts");
        setId(second, 2L);
        when(apiRepository.findAllByOrderByIdAsc()).thenReturn(List.of(first, second));

        List<ApiResponse> responses = apiService.list();

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo(1L);
        assertThat(responses.get(0).contextPath()).isEqualTo("/payments");
        assertThat(responses.get(1).id()).isEqualTo(2L);
        assertThat(responses.get(1).contextPath()).isEqualTo("/accounts");
    }

    private void setId(Api api, Long id) {
        setField(api, "id", id);
    }

    private void setField(Api api, String name, Object value) {
        try {
            java.lang.reflect.Field field = Api.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(api, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}