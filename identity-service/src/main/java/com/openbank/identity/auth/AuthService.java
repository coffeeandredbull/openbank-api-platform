package com.openbank.identity.auth;

import com.openbank.identity.exception.AuthenticationFailedException;
import com.openbank.identity.user.User;
import com.openbank.identity.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private static final String DUMMY_VERIFY_HASH =
            "$2a$10$DIkXj.n7D.acbFqK2peaYOVaA2oaYy8Fuh7UCn9sR/eBjAjgtgqPK";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Optional<User> user = userRepository.findByEmail(request.email());
        if (user.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_VERIFY_HASH);
            throw new AuthenticationFailedException();
        }
        User existing = user.get();
        if (!passwordEncoder.matches(request.password(), existing.getPasswordHash())) {
            throw new AuthenticationFailedException();
        }
        return LoginResponse.from(existing);
    }
}