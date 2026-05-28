package com.arias.catalog.categories;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * Resuelve el árbol de categorías para el chequeo de visibilidad.
 *
 * <p>Regla: un usuario con categoría X puede ver platos de X + todos los descendientes.
 * Ej: Premium → padre de Básico → user Premium ve Premium + Básico.
 */
@Service
@RequiredArgsConstructor
public class CategoryHierarchyService {

    private final CategoryRepository repo;

    /**
     * Devuelve el ID set de la categoría dada + todos sus descendientes (recursivo).
     */
    @Transactional(readOnly = true)
    public Set<Long> visibleCategoryIdsFor(Long rootCategoryId) {
        Set<Long> visible = new HashSet<>();
        if (rootCategoryId == null) return visible;
        visible.add(rootCategoryId);

        // Iterativo + BFS: cargamos todas las categorías una vez y armamos el árbol en memoria.
        // Es O(n) por usuario, y n es pequeño (≤ 10 típicamente), así que no vale la pena
        // optimizar con CTE recursivo todavía.
        var all = repo.findAll();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var cat : all) {
                if (cat.getParent() != null
                    && visible.contains(cat.getParent().getId())
                    && !visible.contains(cat.getId())) {
                    visible.add(cat.getId());
                    changed = true;
                }
            }
        }
        return visible;
    }
}
