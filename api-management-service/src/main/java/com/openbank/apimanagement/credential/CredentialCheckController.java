package com.openbank.apimanagement.credential;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/credential-check")
public class CredentialCheckController {

    private final CredentialCheckService credentialCheckService;

    public CredentialCheckController(CredentialCheckService credentialCheckService) {
        this.credentialCheckService = credentialCheckService;
    }

    @GetMapping
    public ResponseEntity<CredentialCheckResponse> check(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam String contextPath,
            @RequestParam String version) {
        CredentialCheckResponse result = credentialCheckService.check(authorization, contextPath, version);
        if (!result.authenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }
        return ResponseEntity.ok(result);
    }
}