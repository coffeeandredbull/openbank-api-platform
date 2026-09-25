package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.auth.JwtIdentity;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/applications/{applicationId}/credentials")
public class ApplicationCredentialController {

    private final CredentialService credentialService;

    public ApplicationCredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<CredentialCreatedResponse> create(
            @AuthenticationPrincipal JwtIdentity identity,
            @PathVariable Long applicationId,
            @Valid @RequestBody CreateApplicationCredentialRequest request) {
        CredentialCreatedResponse created = credentialService.create(
                identity.userId(), applicationId, request.expiresAt());
        return ResponseEntity.created(URI.create("/credentials/" + created.id())).body(created);
    }
}
