package com.openbank.payment.transaction;

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
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(
            @AuthenticationPrincipal JwtIdentity identity,
            @Valid @RequestBody CreateTransactionRequest request) {
        TransactionResponse created = transactionService.create(identity.userId(), request);
        return ResponseEntity.created(URI.create("/transactions/" + created.id())).body(created);
    }

    @GetMapping("/{transactionId}")
    public TransactionResponse get(
            @AuthenticationPrincipal JwtIdentity identity,
            @PathVariable Long transactionId) {
        return transactionService.get(transactionId, identity.userId());
    }

    @GetMapping
    public List<TransactionResponse> list(@AuthenticationPrincipal JwtIdentity identity) {
        return transactionService.list(identity.userId());
    }
}