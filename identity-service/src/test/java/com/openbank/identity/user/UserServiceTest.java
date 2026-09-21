package com.openbank.identity.user;

import com.openbank.identity.exception.EmailAlreadyExistsException;
import com.openbank.identity.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void createPersistsUserAndReturnsResponseWithoutPasswordHash() {
        User saved = new User("dev@example.com", "hash-do-not-leak", UserRole.DEVELOPER);
        setId(saved, 42L);
        when(userRepository.existsByEmail("dev@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(saved);

        CreateUserRequest request = new CreateUserRequest("dev@example.com", "hash-do-not-leak", UserRole.DEVELOPER);

        UserResponse response = userService.create(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("dev@example.com");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hash-do-not-leak");
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.DEVELOPER);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo("dev@example.com");
        assertThat(response.role()).isEqualTo(UserRole.DEVELOPER);
        assertThat(response.createdAt()).isNotNull();
        assertThat(passwordHashLeaks(response)).as("passwordHash must never leak in a response").isFalse();
    }

    @Test
    void createRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        CreateUserRequest request = new CreateUserRequest("dup@example.com", "some-hash", UserRole.ADMIN);

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void getReturnsUserWithoutPasswordHash() {
        User existing = new User("dev@example.com", "hash-do-not-leak", UserRole.DEVELOPER);
        setId(existing, 7L);
        setField(existing, "createdAt", Instant.parse("2026-09-21T10:00:00Z"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));

        UserResponse response = userService.get(7L);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.email()).isEqualTo("dev@example.com");
        assertThat(response.role()).isEqualTo(UserRole.DEVELOPER);
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-09-21T10:00:00Z"));
        assertThat(passwordHashLeaks(response)).as("passwordHash must never leak in a response").isFalse();
    }

    @Test
    void getThrowsWhenUserDoesNotExist() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.get(99L))
                .isInstanceOf(UserNotFoundException.class);
    }

    private boolean passwordHashLeaks(UserResponse response) {
        return response.toString().contains("hash");
    }

    private void setId(User user, Long id) {
        setField(user, "id", id);
    }

    private void setField(User user, String name, Object value) {
        try {
            java.lang.reflect.Field field = User.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(user, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}