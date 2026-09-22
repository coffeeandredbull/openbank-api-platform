package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.application.Application;
import com.openbank.apimanagement.application.ApplicationRepository;
import com.openbank.apimanagement.exception.ApplicationNotFoundException;
import com.openbank.apimanagement.exception.CredentialNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class CredentialService {

    private static final int MAX_CLIENT_ID_ATTEMPTS = 5;
    private static final int CLIENT_SECRET_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CredentialRepository credentialRepository;
    private final ApplicationRepository applicationRepository;
    private final PasswordEncoder passwordEncoder;

    public CredentialService(
            CredentialRepository credentialRepository,
            ApplicationRepository applicationRepository,
            PasswordEncoder passwordEncoder) {
        this.credentialRepository = credentialRepository;
        this.applicationRepository = applicationRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public CredentialCreatedResponse create(Long ownerUserId, CreateCredentialRequest request) {
        Application application = applicationRepository
                .findByIdAndOwnerUserId(request.applicationId(), ownerUserId)
                .orElseThrow(() -> new ApplicationNotFoundException(request.applicationId()));

        String plaintextClientSecret = generateClientSecret();
        for (int attempt = 0; attempt < MAX_CLIENT_ID_ATTEMPTS; attempt++) {
            String clientId = UUID.randomUUID().toString();
            if (credentialRepository.existsByClientId(clientId)) {
                continue;
            }
            try {
                Credential saved = credentialRepository.save(new Credential(
                        application, clientId, passwordEncoder.encode(plaintextClientSecret)));
                return CredentialCreatedResponse.from(saved, plaintextClientSecret);
            } catch (DataIntegrityViolationException e) {
                // Concurrent insert won the unique(client_id) race: regenerate and retry.
            }
        }
        throw new IllegalStateException("Unable to generate a unique client id");
    }

    @Transactional(readOnly = true)
    public CredentialResponse get(Long credentialId, Long ownerUserId) {
        Credential credential = credentialRepository
                .findByIdAndApplication_OwnerUserId(credentialId, ownerUserId)
                .orElseThrow(() -> new CredentialNotFoundException(credentialId));
        return CredentialResponse.from(credential);
    }

    @Transactional(readOnly = true)
    public List<CredentialResponse> list(Long ownerUserId) {
        return credentialRepository.findByApplication_OwnerUserIdOrderByIdAsc(ownerUserId).stream()
                .map(CredentialResponse::from)
                .toList();
    }

    private String generateClientSecret() {
        byte[] bytes = new byte[CLIENT_SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}