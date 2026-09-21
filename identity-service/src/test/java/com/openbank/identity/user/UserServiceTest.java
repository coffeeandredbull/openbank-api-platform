package com.openbank.identity.user;

import com.openbank.identity.exception.EmailAlreadyExistsException;
import com.openbank.identity.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks
    private UserService userService;

    @Test
    void createHashesPasswordAndReturnsResponseWithoutSecrets() {
        User saved = new User("dev@example.com", "unused-placeholder", UserRole.DEVELOPER);
        setId(saved, 42L);
        when(userRepository.existsByEmail("dev@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(saved);

        CreateUserRequest request = new CreateUserRequest("dev@example.com", "SuperSecret!123", UserRole.DEVELOPER);

        UserResponse response = userService.create(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User persisted = captor.getValue();
        String storedHash = persisted.getPasswordHash();

        assertThat(persisted.getEmail()).isEqualTo("dev@example.com");
        assertThat(persisted.getRole()).isEqualTo(UserRole.DEVELOPER);
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(storedHash)
                .as("stored value must be a BCrypt hash, not the plaintext password")
                .isNotEqualTo("SuperSecret!123")
                .startsWith("$2");
        assertThat(passwordEncoder.matches("SuperSecret!123", storedHash))
                .as("the plaintext password must verify against the stored hash")
                .isTrue();
        assertThat(passwordEncoder.matches("WrongPassword", storedHash)).isFalse();

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo("dev@example.com");
        assertThat(response.role()).isEqualTo(UserRole.DEVELOPER);
        assertThat(response.createdAt()).isNotNull();
        assertThat(noSecretLeaks(response, "SuperSecret!123", storedHash))
                .as("neither the plaintext password nor the hash may appear in the response")
                .isTrue();
    }

    @Test
    void createRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        CreateUserRequest request = new CreateUserRequest("dup@example.com", "some-password", UserRole.ADMIN);

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void getReturnsUserWithoutPasswordHash() {
        User existing = new User("dev@example.com", "some-existing-hash", UserRole.DEVELOPER);
        setId(existing, 7L);
        setField(existing, "createdAt", Instant.parse("2026-09-21T10:00:00Z"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));

        UserResponse response = userService.get(7L);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.email()).isEqualTo("dev@example.com");
        assertThat(response.role()).isEqualTo(UserRole.DEVELOPER);
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-09-21T10:00:00Z"));
        assertThat(noSecretLeaks(response, "some-existing-hash", "some-existing-hash"))
                .as("passwordHash must never leak in a response")
                .isTrue();
    }

    @Test
    void getThrowsWhenUserDoesNotExist() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.get(99L))
                .isInstanceOf(UserNotFoundException.class);
    }

    private boolean noSecretLeaks(UserResponse response, String plaintextPassword, String storedHash) {
        String body = response.toString();
        return !body.contains(plaintextPassword)
                && !body.contains(storedHash)
                && !body.contains("passwordHash")
                && !body.contains("pending-password");
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