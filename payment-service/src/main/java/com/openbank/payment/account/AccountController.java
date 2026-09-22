package com.openbank.payment.account;

import com.openbank.payment.auth.JwtIdentity;
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
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(
            @AuthenticationPrincipal JwtIdentity identity,
            @Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = accountService.create(identity.userId(), request);
        return ResponseEntity.created(URI.create("/accounts/" + created.id())).body(created);
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(
            @AuthenticationPrincipal JwtIdentity identity,
            @PathVariable Long accountId) {
        return accountService.get(accountId, identity.userId());
    }

    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal JwtIdentity identity) {
        return accountService.list(identity.userId());
    }
}