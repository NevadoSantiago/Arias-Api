package com.arias.catalog.dishes;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryHierarchyService;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.catalog.sides.Side;
import com.arias.catalog.sides.SideRepository;
import com.arias.catalog.sides.SideType;
import com.arias.common.exception.BusinessException;
import java.util.Comparator;
import com.arias.email.DisableNotificationEmails;
import com.arias.email.DisableNotificationEmails.Reason;
import com.arias.orders.AffectedOrderDto;
import com.arias.orders.DailyChoice;
import com.arias.orders.DailyChoiceRepository;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DishService {

    private final DishRepository dishRepo;
    private final CategoryRepository categoryRepo;
    private final MenuSectionRepository menuSectionRepo;
    private final SideRepository sideRepo;
    private final CategoryHierarchyService categoryHierarchy;
    private final DailyChoiceRepository orderRepo;
    private final RestaurantConfigRepository configRepo;
    private final DisableNotificationEmails emails;

    // ─── Empleado: lista disponible filtrada por categoría ───────────────

    @Transactional(readOnly = true)
    public List<DishDto> listAvailableFor(Long userCategoryId) {
        return listAvailableFor(userCategoryId, null);
    }

    @Transactional(readOnly = true)
    public List<DishDto> listAvailableFor(Long userCategoryId, LocalDate fecha) {
        if (userCategoryId == null) return List.of();
        LocalDate targetDate = fecha != null ? fecha : LocalDate.now();
        boolean isFuture = targetDate.isAfter(LocalDate.now());

        Set<Long> visible = categoryHierarchy.visibleCategoryIdsFor(userCategoryId);

        List<Dish> dishes;
        if (isFuture) {
            dishes = dishRepo.findAvailableForCategoriesNoStock(visible, targetDate);
        } else {
            dishes = dishRepo.findAvailableForCategories(visible, targetDate);
        }

        return dishes.stream()
            .sorted(Comparator.comparingInt((Dish d) -> d.getMenuSection().getOrdenDisplay())
                    .thenComparing(Dish::getNombre))
            .map(DishDto::from)
            .toList();
    }

    // ─── Admin: CRUD completo ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminDishDto> listAllForAdmin() {
        return dishRepo.findAll().stream()
            .sorted(Comparator
                .comparingInt((Dish d) -> d.getMenuSection().getOrdenDisplay())
                .thenComparing(d -> d.getNombre().toLowerCase()))
            .map(AdminDishDto::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminDishDto> listSpecialForAdmin() {
        return dishRepo.findAll().stream()
            .filter(d -> Boolean.TRUE.equals(d.getEspecial()) && Boolean.TRUE.equals(d.getEnabled()))
            .sorted(Comparator
                .comparingInt((Dish d) -> d.getMenuSection().getOrdenDisplay())
                .thenComparing(d -> d.getNombre().toLowerCase()))
            .map(AdminDishDto::from)
            .toList();
    }

    @Transactional
    public AdminDishDto create(CreateDishRequest req) {
        Category category = findCategory(req.categoryId());
        MenuSection menuSection = findMenuSection(req.menuSectionId());
        Set<Side> sides = validateAndResolveSides(req.sideType(), req.allowedSideIds());

        boolean esEspecial = req.especial() != null && req.especial();

        Dish dish = Dish.builder()
            .nombre(req.nombre().trim())
            .descripcion(trimOrNull(req.descripcion()))
            .fotoUrl(trimOrNull(req.fotoUrl()))
            .category(category)
            .menuSection(menuSection)
            .sideType(req.sideType())
            .allowedSides(sides)
            .stockDiarioDefault(req.stockDiarioDefault())
            .stockActual(req.stockActual())
            .enabled(true)
            .especial(esEspecial)
            .build();

        return AdminDishDto.from(dishRepo.save(dish));
    }

    @Transactional
    public AdminDishDto update(Long id, UpdateDishRequest req) {
        Dish dish = findDish(id);
        Category category = findCategory(req.categoryId());
        MenuSection menuSection = findMenuSection(req.menuSectionId());
        Set<Side> sides = validateAndResolveSides(req.sideType(), req.allowedSideIds());

        boolean esEspecial = req.especial() != null && req.especial();

        dish.setNombre(req.nombre().trim());
        dish.setDescripcion(trimOrNull(req.descripcion()));
        dish.setFotoUrl(trimOrNull(req.fotoUrl()));
        dish.setCategory(category);
        dish.setMenuSection(menuSection);
        dish.setSideType(req.sideType());
        dish.setAllowedSides(sides);
        dish.setStockDiarioDefault(req.stockDiarioDefault());
        dish.setStockActual(req.stockActual());
        dish.setEnabled(req.enabled());
        dish.setEspecial(esEspecial);

        return AdminDishDto.from(dish);
    }

    /** Pedidos PENDIENTE que se verían afectados si desactivamos este plato. */
    @Transactional(readOnly = true)
    public List<AffectedOrderDto> findAffectedOrders(Long id) {
        // Verificamos que el dish existe (sino devolvemos error en vez de lista vacía)
        findDish(id);
        return orderRepo.findPendingByDish(id).stream()
            .map(AffectedOrderDto::from)
            .toList();
    }

    /**
     * Desactiva el plato. Si {@code cancelAffected} es true, también cancela
     * los pedidos pendientes que lo tenían (devolviendo el stock). En ambos
     * casos, manda mails a los empleados afectados.
     */
    @Transactional
    public void disable(Long id, boolean cancelAffected) {
        Dish dish = findDish(id);
        dish.setEnabled(false);

        List<DailyChoice> affected = orderRepo.findPendingByDish(id);
        LocalTime cutoff = configRepo.getSingleton().getHoraCorte();

        for (DailyChoice order : affected) {
            if (cancelAffected) {
                // Cancelamos: devolvemos stock y borramos el pedido
                dishRepo.incrementStock(order.getDish().getId());
                emails.notifyOrderCancelled(order, Reason.DISH_DISABLED, cutoff);
                orderRepo.delete(order);
            } else {
                emails.notifyOrderKept(order, Reason.DISH_DISABLED, cutoff);
            }
        }
    }

    @Transactional
    public void enable(Long id) {
        findDish(id).setEnabled(true);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private Dish findDish(Long id) {
        return dishRepo.findById(id)
            .orElseThrow(() -> BusinessException.notFound("dish-not-found", "Plato no encontrado"));
    }

    private Category findCategory(Long id) {
        return categoryRepo.findById(id)
            .orElseThrow(() -> BusinessException.notFound("category-not-found", "Categoría no encontrada"));
    }

    private MenuSection findMenuSection(Long id) {
        return menuSectionRepo.findById(id)
            .orElseThrow(() -> BusinessException.notFound("section-not-found", "Sección no encontrada"));
    }

    /**
     * Valida la coherencia entre sideType y la lista de allowedSideIds.
     * Reglas:
     *   - Si sideType == null → allowedSideIds debe estar vacío
     *   - Si sideType tiene valor → todos los sides deben ser del mismo tipo
     */
    private Set<Side> validateAndResolveSides(SideType sideType, List<Long> allowedSideIds) {
        List<Long> ids = allowedSideIds != null ? allowedSideIds : List.of();

        if (sideType == null) {
            if (!ids.isEmpty()) {
                throw BusinessException.badRequest("side-type-required",
                    "Para asociar acompañamientos, definí el tipo (Guarnición o Salsa)");
            }
            return new HashSet<>();
        }

        if (ids.isEmpty()) {
            // Tipo definido pero sin sides asociados — válido (el admin todavía no eligió ninguno)
            return new HashSet<>();
        }

        List<Side> sides = sideRepo.findAllById(ids);
        if (sides.size() != ids.size()) {
            throw BusinessException.badRequest("side-not-found",
                "Alguno de los acompañamientos seleccionados no existe");
        }

        boolean tipoMismatch = sides.stream().anyMatch(s -> s.getTipo() != sideType);
        if (tipoMismatch) {
            throw BusinessException.badRequest("side-type-mismatch",
                "Todos los acompañamientos asociados deben ser del tipo " + sideType);
        }

        return new HashSet<>(sides);
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
