package com.openbank.identity.auth;

import com.openbank.identity.exception.AuthenticationFailedException;
import com.openbank.identity.user.User;
import com.openbank.identity.user.UserRepository;
import com.openbank.identity.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private TokenRevocationService tokenRevocationService;

    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks
    private AuthService authService;

    @Test
    void loginReturnsSafeIdentityWhenCredentialsAreValid() {
        User user = new User("dev@example.com", passwordEncoder.encode("Correct-Horse-42"), UserRole.DEVELOPER);
        setId(user, 123L);
        when(userRepository.findByEmail("dev@example.com")).thenReturn(Optional.of(user));
        when(jwtTokenService.generateAccessToken(123L, UserRole.DEVELOPER)).thenReturn("header.payload.signature");
        when(jwtTokenService.expiresInSeconds()).thenReturn(3600L);

        LoginResponse response = authService.login(new LoginRequest("dev@example.com", "Correct-Horse-42"));

        assertThat(response.accessToken()).isEqualTo("header.payload.signature");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600L);
        assertThat(response.userId()).isEqualTo(123L);
        assertThat(response.email()).isEqualTo("dev@example.com");
        assertThat(response.role()).isEqualTo(UserRole.DEVELOPER);
        assertThat(response.toString())
                .as("no password or hash may appear in the login response")
                .doesNotContain("Correct-Horse-42")
                .doesNotContain("passwordHash")
                .doesNotContain(user.getPasswordHash());
        verify(passwordEncoder).matches("Correct-Horse-42", user.getPasswordHash());
        verify(jwtTokenService).generateAccessToken(123L, UserRole.DEVELOPER);
    }

    @Test
    void loginWithIncorrectPasswordThrowsAuthenticationFailed() {
        User user = new User("dev@example.com", passwordEncoder.encode("Correct-Horse-42"), UserRole.DEVELOPER);
        when(userRepository.findByEmail("dev@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("dev@example.com", "Wrong-Password")))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage(AuthenticationFailedException.MESSAGE);
        verify(jwtTokenService, never()).generateAccessToken(any(), any());
    }

    @Test
    void loginWithUnknownEmailThrowsSameAuthenticationFailed() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@example.com", "Any-Password")))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage(AuthenticationFailedException.MESSAGE);
        verify(jwtTokenService, never()).generateAccessToken(any(), any());
    }

    @Test
    void unknownEmailAndWrongPasswordProduceIdenticalFailure() {
        User user = new User("dev@example.com", passwordEncoder.encode("Correct-Horse-42"), UserRole.DEVELOPER);
        when(userRepository.findByEmail("dev@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        Throwable wrongPassword = catchFailure(new LoginRequest("dev@example.com", "Wrong-Password"));
        Throwable unknownEmail = catchFailure(new LoginRequest("ghost@example.com", "Wrong-Password"));

        assertThat(wrongPassword).isInstanceOf(AuthenticationFailedException.class);
        assertThat(unknownEmail).isInstanceOf(AuthenticationFailedException.class);
        assertThat(wrongPassword.getMessage()).isEqualTo(unknownEmail.getMessage());
        assertThat(wrongPassword.getClass()).isEqualTo(unknownEmail.getClass());
    }

    private Throwable catchFailure(LoginRequest request) {
        try {
            authService.login(request);
            throw new AssertionError("login should have failed");
        } catch (AuthenticationFailedException e) {
            return e;
        }
    }

    @Test
    void revokeDerivesTheJtiFromTheSignedTokenAndPersistsTheRevocation() {
        Instant expiresAt = Instant.parse("2026-01-01T01:00:00Z");
        when(jwtTokenService.validateAndExtractJti("header.payload.signature"))
                .thenReturn(new JwtTokenService.ValidJwt("jti-abc", expiresAt));

        RevokeResponse response = authService.revoke("header.payload.signature");

        assertThat(response.revoked()).isTrue();
        verify(jwtTokenService).validateAndExtractJti("header.payload.signature");
        verify(tokenRevocationService).revoke("jti-abc", expiresAt);
    }

    private void setId(User user, Long id) {
        try {
            java.lang.reflect.Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}