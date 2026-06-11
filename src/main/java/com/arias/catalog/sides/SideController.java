package com.arias.catalog.sides;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sides")
@RequiredArgsConstructor
public class SideController {

    private final SideRepository repo;
    private final SideService service;

    /** Lista pública para autenticados (usado por dropdowns) — solo activos. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<SideDto> list() {
        return repo.findAllByEnabledTrueAndDeletedAtIsNullOrderByTipoAscNombreAsc().stream()
            .map(SideDto::from)
            .toList();
    }

    // ─── Admin endpoints ───────────────────────────────────────────────

    @GetMapping("/admin")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<SideDto> listAdmin() {
        return service.listAll();
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public SideDto create(@Valid @RequestBody CreateSideRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public SideDto update(@PathVariable Long id, @Valid @RequestBody UpdateSideRequest req) {
        return service.update(id, req);
    }

    @GetMapping("/{id}/affected-orders")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<com.arias.orders.AffectedOrderDto> affectedOrders(@PathVariable Long id) {
        return service.findAffectedOrders(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> disable(@PathVariable Long id) {
        service.disable(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/enable")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> enable(@PathVariable Long id) {
        service.enable(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Soft-delete del acompañamiento. Requiere que esté deshabilitado primero.
     * Mismo patrón que el archive de platos y categorías.
     */
    @DeleteMapping("/{id}/archive")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        service.archive(id);
        return ResponseEntity.noContent().build();
    }
}
