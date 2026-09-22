package com.openbank.payment.payment;

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
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @AuthenticationPrincipal JwtIdentity identity,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentResponse created = paymentService.create(identity.userId(), request);
        return ResponseEntity.created(URI.create("/payments/" + created.id())).body(created);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(
            @AuthenticationPrincipal JwtIdentity identity,
            @PathVariable Long paymentId) {
        return paymentService.get(paymentId, identity.userId());
    }

    @GetMapping
    public List<PaymentResponse> list(@AuthenticationPrincipal JwtIdentity identity) {
        return paymentService.list(identity.userId());
    }
}