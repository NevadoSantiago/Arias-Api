package com.arias.catalog.menusections;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/menu-sections")
@RequiredArgsConstructor
public class MenuSectionController {

    private final MenuSectionRepository repo;
    private final MenuSectionService service;

    /** Listado público para usuarios autenticados (filter pills del empleado). */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<MenuSectionDto> list() {
        return repo.findAllByEnabledTrueOrderByOrdenDisplayAsc().stream()
            .map(MenuSectionDto::from)
            .toList();
    }

    // ─── A partir de acá solo SUPER_ADMIN ─────────────────────────────

    /** Listado completo (incluye desactivadas) — para el panel admin. */
    @GetMapping("/admin")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<MenuSectionDto> listAdmin() {
        return service.listAll();
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public MenuSectionDto create(@Valid @RequestBody CreateMenuSectionRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public MenuSectionDto update(@PathVariable Long id, @Valid @RequestBody UpdateMenuSectionRequest req) {
        return service.update(id, req);
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
}
