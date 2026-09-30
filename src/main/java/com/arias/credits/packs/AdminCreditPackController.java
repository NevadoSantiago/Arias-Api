package com.arias.credits.packs;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD de paquetes de créditos — {@code SUPER_ADMIN} (unidad 11, tarea 11.2).
 * El catálogo público (para elegir qué comprar) vive en
 * {@link CreditPackController}, bajo {@code /api/v1/credits/packs}.
 */
@RestController
@RequestMapping("/api/v1/admin/credit-packs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminCreditPackController {

    private final CreditPackService service;

    @GetMapping
    public List<CreditPackDto> list() {
        return service.listAdmin();
    }

    @PostMapping
    public CreditPackDto create(@Valid @RequestBody CreateCreditPackRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public CreditPackDto update(@PathVariable Long id, @Valid @RequestBody UpdateCreditPackRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
