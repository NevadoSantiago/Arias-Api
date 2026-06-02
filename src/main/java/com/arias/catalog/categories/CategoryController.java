package com.arias.catalog.categories;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints de categorías de acceso (Premium, Básico, etc.).
 *
 * <p>GET / — público para SUPER_ADMIN y COMPANY_ADMIN: dropdown del form de empresas/empleados.
 * Solo lista categorías habilitadas, ordenadas por orden display.
 *
 * <p>Resto de endpoints (CRUD) — solo SUPER_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryRepository repo;
    private final CategoryService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMPANY_ADMIN')")
    public List<CategoryDto> list() {
        return repo.findAllByEnabledTrueOrderByOrdenDisplayAsc().stream()
            .map(CategoryDto::from)
            .toList();
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<AdminCategoryDto> listAdmin() {
        return service.listAllForAdmin();
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AdminCategoryDto create(@Valid @RequestBody CreateCategoryRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AdminCategoryDto update(@PathVariable Long id, @Valid @RequestBody UpdateCategoryRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void disable(@PathVariable Long id) {
        service.disable(id);
    }

    @PutMapping("/{id}/enable")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void enable(@PathVariable Long id) {
        service.enable(id);
    }

    /**
     * Soft-delete de la categoría. Requiere que esté deshabilitada primero.
     * Cascade: deshabilita todos los platos activos asociados.
     */
    @DeleteMapping("/{id}/archive")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void archive(@PathVariable Long id) {
        service.archive(id);
    }

    /** Preview: cuántos platos activos quedarían deshabilitados al archivar esta categoría. */
    @GetMapping("/{id}/affected-dishes-count")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public long affectedDishesCount(@PathVariable Long id) {
        return service.countActiveDishesForCategory(id);
    }
}
