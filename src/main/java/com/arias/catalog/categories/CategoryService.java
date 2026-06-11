package com.arias.catalog.categories;

import com.arias.catalog.dishes.DishRepository;
import com.arias.common.exception.BusinessException;
import com.arias.companies.Company;
import com.arias.companies.CompanyCategoryPrice;
import com.arias.companies.CompanyCategoryPriceRepository;
import com.arias.companies.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class CategoryService {

    private final CategoryRepository repo;
    private final CompanyRepository companyRepo;
    private final CompanyCategoryPriceRepository priceRepo;
    private final DishRepository dishRepo;

    @Transactional(readOnly = true)
    public List<AdminCategoryDto> listAllForAdmin() {
        return repo.findAllNotDeleted().stream()
            .sorted(Comparator
                .comparingInt(Category::getOrdenDisplay)
                .thenComparing(c -> c.getNombre().toLowerCase()))
            .map(this::toDto)
            .toList();
    }

    @Transactional
    public AdminCategoryDto create(CreateCategoryRequest req) {
        String nombre = req.nombre().trim();
        Category existing = repo.findByNombreIncludingDeleted(nombre).orElse(null);

        if (existing != null && existing.getDeletedAt() == null) {
            throw BusinessException.conflict("category-name-duplicate",
                "Ya existe una categoría con ese nombre");
        }

        Category parent = resolveParent(req.parentId(), existing != null ? existing.getId() : null);
        validateAllCompaniesPriced(req.companyPrices());

        // Resurrección: crear con el nombre de una categoría archivada la
        // restaura, pero con TODOS los datos del request (parent, orden,
        // precios) — no el estado zombie que tenía al archivarse. Los platos
        // que el archive deshabilitó en cascada NO se reactivan solos.
        if (existing != null) {
            existing.setNombre(nombre);
            existing.setParent(parent);
            existing.setOrdenDisplay(req.ordenDisplay());
            existing.setEnabled(true);
            existing.setDeletedAt(null);

            priceRepo.deleteByCategoryId(existing.getId());
            priceRepo.flush();
            savePrices(existing.getId(), req.companyPrices());

            return toDto(existing);
        }

        Category category = Category.builder()
            .nombre(nombre)
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
            // Incluye archivadas: el UNIQUE de la base las cuenta igual, y sin
            // este chequeo el rename explotaría con un 500 de constraint.
            repo.findByNombreIncludingDeleted(req.nombre().trim()).ifPresent(other -> {
                if (!other.getId().equals(id)) {
                    throw BusinessException.conflict("category-name-duplicate",
                        other.getDeletedAt() == null
                            ? "Ya existe otra categoría con ese nombre"
                            : "Ese nombre pertenece a una categoría eliminada. " +
                              "Crearla de nuevo desde 'Nueva categoría' la restaura.");
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

    /**
     * Deshabilita la categoría. Cascade: también deshabilita todos los platos
     * activos asociados (para que no queden visibles en el menú con su categoría
     * apagada).
     */
    @Transactional
    public void disable(Long id) {
        Category category = findOrThrow(id);
        int disabled = dishRepo.disableAllByCategoryId(id);
        category.setEnabled(false);
        log.info("Categoría deshabilitada: {} (id={}) — {} platos deshabilitados en cascada",
            category.getNombre(), id, disabled);
    }

    @Transactional
    public void enable(Long id) {
        findOrThrow(id).setEnabled(true);
    }

    /**
     * Soft-delete de la categoría. Setea {@code deletedAt = now}. Requiere
     * estar previamente deshabilitada.
     *
     * <p>Cascade: deshabilita TODOS los platos activos asociados a esta categoría
     * para evitar que queden huérfanos (categoría no visible pero plato apuntando
     * a ella). Los pedidos históricos quedan intactos (snapshots preservados).
     */
    @Transactional
    public void archive(Long id) {
        Category category = findOrThrow(id);
        if (Boolean.TRUE.equals(category.getEnabled())) {
            throw BusinessException.badRequest("must-disable-first",
                "Primero desactivá la categoría, después podés borrarla");
        }
        int disabled = dishRepo.disableAllByCategoryId(id);
        category.setDeletedAt(java.time.Instant.now());
        log.info("Categoría archivada: {} (id={}) — {} platos deshabilitados en cascada",
            category.getNombre(), id, disabled);
    }

    /** Conteo de platos activos asociados a la categoría — para preview del modal. */
    @Transactional(readOnly = true)
    public long countActiveDishesForCategory(Long id) {
        findOrThrow(id);
        return dishRepo.countActiveByCategoryId(id);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private Category findOrThrow(Long id) {
        Category c = repo.findById(id)
            .orElseThrow(() -> BusinessException.notFound("category-not-found",
                "Categoría no encontrada"));
        if (c.getDeletedAt() != null) {
            throw BusinessException.notFound("category-not-found", "Categoría no encontrada");
        }
        return c;
    }

    private AdminCategoryDto toDto(Category c) {
        Map<Long, Integer> prices = priceRepo.findByCategoryId(c.getId()).stream()
            .collect(Collectors.toMap(
                CompanyCategoryPrice::getCompanyId,
                CompanyCategoryPrice::getPrecio));
        return AdminCategoryDto.from(c, prices);
    }

    /**
     * Verifica que el map de precios incluya TODAS las empresas existentes (no soft-deleted).
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
