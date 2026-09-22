package com.openbank.apimanagement.application;

import com.openbank.apimanagement.auth.JwtIdentity;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public ResponseEntity<ApplicationResponse> create(
            @AuthenticationPrincipal JwtIdentity identity,
            @Valid @RequestBody CreateApplicationRequest request) {
        ApplicationResponse created = applicationService.create(identity.userId(), request);
        return ResponseEntity.created(URI.create("/applications/" + created.id())).body(created);
    }

    @GetMapping("/{applicationId}")
    public ApplicationResponse get(
            @AuthenticationPrincipal JwtIdentity identity,
            @PathVariable Long applicationId) {
        return applicationService.get(applicationId, identity.userId());
    }

    @GetMapping
    public List<ApplicationResponse> list(@AuthenticationPrincipal JwtIdentity identity) {
        return applicationService.list(identity.userId());
    }

    @PatchMapping("/{applicationId}")
    public ApplicationResponse update(
            @AuthenticationPrincipal JwtIdentity identity,
            @PathVariable Long applicationId,
            @Valid @RequestBody UpdateApplicationRequest request) {
        return applicationService.update(applicationId, identity.userId(), request);
    }
}