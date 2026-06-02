package com.arias.catalog.categories;

import com.arias.common.exception.BusinessException;
import com.arias.companies.Company;
import com.arias.companies.CompanyCategoryPrice;
import com.arias.companies.CompanyCategoryPriceRepository;
import com.arias.companies.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository repo;
    private final CompanyRepository companyRepo;
    private final CompanyCategoryPriceRepository priceRepo;

    @Transactional(readOnly = true)
    public List<AdminCategoryDto> listAllForAdmin() {
        return repo.findAll().stream()
            .sorted(Comparator
                .comparingInt(Category::getOrdenDisplay)
                .thenComparing(c -> c.getNombre().toLowerCase()))
            .map(this::toDto)
            .toList();
    }

    @Transactional
    public AdminCategoryDto create(CreateCategoryRequest req) {
        repo.findByNombre(req.nombre().trim()).ifPresent(c -> {
            throw BusinessException.conflict("category-name-duplicate",
                "Ya existe una categoría con ese nombre");
        });

        Category parent = resolveParent(req.parentId(), null);
        validateAllCompaniesPriced(req.companyPrices());

        Category category = Category.builder()
            .nombre(req.nombre().trim())
            .parent(parent)
            .ordenDisplay(req.ordenDisplay())
            .enabled(true)
            .build();
        Category saved = repo.save(category);

        savePrices(saved.getId(), req.companyPrices());

        return toDto(saved);
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
        validateAllCompaniesPriced(req.companyPrices());

        category.setNombre(req.nombre().trim());
        category.setParent(parent);
        category.setOrdenDisplay(req.ordenDisplay());
        category.setEnabled(req.enabled());

        // Upsert de precios
        priceRepo.deleteByCategoryId(id);
        priceRepo.flush();
        savePrices(id, req.companyPrices());

        return toDto(category);
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

    private AdminCategoryDto toDto(Category c) {
        Map<Long, Integer> prices = priceRepo.findByCategoryId(c.getId()).stream()
            .collect(Collectors.toMap(
                CompanyCategoryPrice::getCompanyId,
                CompanyCategoryPrice::getPrecio));
        return AdminCategoryDto.from(c, prices);
    }

    /**
     * Verifica que el map de precios incluya TODAS las empresas existentes.
     * Si falta alguna o sobra una inexistente, error.
     */
    private void validateAllCompaniesPriced(Map<Long, Integer> prices) {
        Set<Long> existing = companyRepo.findAll().stream()
            .map(Company::getId)
            .collect(Collectors.toSet());
        Set<Long> provided = prices == null ? Set.of() : prices.keySet();

        Set<Long> missing = new HashSet<>(existing);
        missing.removeAll(provided);
        if (!missing.isEmpty()) {
            throw BusinessException.badRequest("missing-company-prices",
                "Faltan precios para las empresas: " + missing);
        }

        Set<Long> extra = new HashSet<>(provided);
        extra.removeAll(existing);
        if (!extra.isEmpty()) {
            throw BusinessException.badRequest("invalid-company-prices",
                "Hay precios para empresas que no existen: " + extra);
        }

        for (Integer precio : prices.values()) {
            if (precio == null || precio < 0) {
                throw BusinessException.badRequest("invalid-precio",
                    "El precio debe ser un número mayor o igual a 0");
            }
        }
    }

    private void savePrices(Long categoryId, Map<Long, Integer> prices) {
        for (var entry : prices.entrySet()) {
            priceRepo.save(CompanyCategoryPrice.builder()
                .companyId(entry.getKey())
                .categoryId(categoryId)
                .precio(entry.getValue())
                .build());
        }
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
