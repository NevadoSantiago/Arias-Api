package com.arias.payments;

import com.arias.common.security.JwtUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Inicio y consulta de compras de créditos (unidad 11, tarea 11.3). El
 * webhook que efectivamente acredita vive en {@link
 * MercadoPagoWebhookController} — este controller solo crea la compra
 * {@code PENDING} y redirige al checkout.
 */
@RestController
@RequestMapping("/api/v1/credits/purchases")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class CreditPurchaseController {

    private final CreditPurchaseService service;

    @PostMapping
    public CreditPurchaseCheckoutDto create(
        @AuthenticationPrincipal JwtUser user,
        @Valid @RequestBody CreatePurchaseRequest req
    ) {
        return service.createPurchase(user.userId(), req);
    }

    @GetMapping("/{id}")
    public CreditPurchaseDto get(@AuthenticationPrincipal JwtUser user, @PathVariable UUID id) {
        return service.getPurchase(user.userId(), id);
    }
}
