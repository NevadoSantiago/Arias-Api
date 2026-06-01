package com.arias.catalog.categories;

import com.arias.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository repo;

    @Transactional(readOnly = true)
    public List<AdminCategoryDto> listAllForAdmin() {
        return repo.findAll().stream()
            .sorted(Comparator
                .comparingInt(Category::getOrdenDisplay)
                .thenComparing(c -> c.getNombre().toLowerCase()))
            .map(AdminCategoryDto::from)
            .toList();
    }

    @Transactional
    public AdminCategoryDto create(CreateCategoryRequest req) {
        repo.findByNombre(req.nombre().trim()).ifPresent(c -> {
            throw BusinessException.conflict("category-name-duplicate",
                "Ya existe una categoría con ese nombre");
        });

        Category parent = resolveParent(req.parentId(), null);

        Category category = Category.builder()
            .nombre(req.nombre().trim())
            .parent(parent)
            .ordenDisplay(req.ordenDisplay())
            .enabled(true)
            .build();
        return AdminCategoryDto.from(repo.save(category));
    }

    @Transactional
    public AdminCategoryDto update(Long id, UpdateCategoryRequest req) {
        Category category = findOrThrow(id);

        if (!category.getNombre().equalsIgnoreCase(req.nombre())) {
            repo.findByNombre(req.nombre().trim()).ifPresent(other -> {
                if (!other.getId().equals(id)) {
                    throw BusinessException.conflict("category-name-duplicate",
                        "Ya existe otra categoría con ese nombre");
                }
            });
        }

        Category parent = resolveParent(req.parentId(), id);

        category.setNombre(req.nombre().trim());
        category.setParent(parent);
        category.setOrdenDisplay(req.ordenDisplay());
        category.setEnabled(req.enabled());
        return AdminCategoryDto.from(category);
    }

    @Transactional
    public void disable(Long id) {
        findOrThrow(id).setEnabled(false);
    }

    @Transactional
    public void enable(Long id) {
        findOrThrow(id).setEnabled(true);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private Category findOrThrow(Long id) {
        return repo.findById(id)
            .orElseThrow(() -> BusinessException.notFound("category-not-found",
                "Categoría no encontrada"));
    }

    /**
     * Valida el parentId y previene auto-referencias y ciclos cortos (A → A).
     * No detecta ciclos largos (A → B → A) — confiamos en que el admin no los crea.
     */
    private Category resolveParent(Long parentId, Long selfId) {
        if (parentId == null) return null;
        if (selfId != null && parentId.equals(selfId)) {
            throw BusinessException.badRequest("category-self-parent",
                "Una categoría no puede ser padre de sí misma");
        }
        return repo.findById(parentId)
            .orElseThrow(() -> BusinessException.notFound("parent-category-not-found",
                "La categoría padre no existe"));
    }
}
