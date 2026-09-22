package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.auth.JwtIdentity;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/credentials")
public class CredentialController {

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<CredentialCreatedResponse> create(
            @AuthenticationPrincipal JwtIdentity identity,
            @Valid @RequestBody CreateCredentialRequest request) {
        CredentialCreatedResponse created = credentialService.create(identity.userId(), request);
        return ResponseEntity.created(URI.create("/credentials/" + created.id())).body(created);
    }

    @GetMapping("/{credentialId}")
    public CredentialResponse get(
            @AuthenticationPrincipal JwtIdentity identity,
            @PathVariable Long credentialId) {
        return credentialService.get(credentialId, identity.userId());
    }

    @GetMapping
    public List<CredentialResponse> list(@AuthenticationPrincipal JwtIdentity identity) {
        return credentialService.list(identity.userId());
    }
}