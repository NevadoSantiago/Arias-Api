package com.arias.credits.packs;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Catálogo público de paquetes de créditos — cualquier usuario autenticado
 * puede consultarlo para elegir qué comprar (diseño, endpoints expuestos:
 * {@code GET /api/v1/credits/packs}). La edición vive en
 * {@link AdminCreditPackController}, restringida a {@code SUPER_ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/credits/packs")
@RequiredArgsConstructor
public class CreditPackController {

    private final CreditPackService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<CreditPackDto> list() {
        return service.listPublic();
    }
}
