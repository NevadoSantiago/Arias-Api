package com.arias.catalog.categories;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints de categorías de acceso (Premium, Básico, etc.).
 * SUPER_ADMIN para los forms de empresas y platos.
 * COMPANY_ADMIN para el dropdown de cambiar categoría a sus empleados.
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMPANY_ADMIN')")
public class CategoryController {

    private final CategoryRepository repo;

    @GetMapping
    public List<CategoryDto> list() {
        return repo.findAllByEnabledTrueOrderByOrdenDisplayAsc().stream()
            .map(CategoryDto::from)
            .toList();
    }
}
