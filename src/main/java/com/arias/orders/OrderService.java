package com.arias.orders;

import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.sides.Side;
import com.arias.catalog.sides.SideRepository;
import com.arias.common.exception.BusinessException;
import com.arias.restaurantconfig.FechaDeshabilitadaRepository;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Lógica del pedido del día (DailyChoice).
 *
 * <p>Reglas clave:
 * <ul>
 *   <li>1 pedido por empleado por día (UNIQUE en BD + check explícito)</li>
 *   <li>El plato tiene que estar enabled y con stock > 0 — decremento atómico</li>
 *   <li>Si el plato lleva side, validar que pertenece a allowedSides y matchea sideType</li>
 *   <li>Snapshots de nombre/categoría/side/hora_entrega al confirmar</li>
 *   <li>Edición/cancelación solo si estado=PENDIENTE (no si ya pasó el corte)</li>
 *   <li>Cancelar/editar devuelve stock al plato original</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final DailyChoiceRepository orderRepo;
    private final UserRepository userRepo;
    private final DishRepository dishRepo;
    private final SideRepository sideRepo;
    private final RestaurantConfigRepository configRepo;
    private final FechaDeshabilitadaRepository fechaDeshabilitadaRepo;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<DailyChoice> findTodayOrder(Long userId) {
        return orderRepo.findByUserIdAndFecha(userId, LocalDate.now(clock));
    }

    /**
     * Sugerencia para el cartelito "El último [día] pediste:". Devuelve el último
     * pedido del usuario en el mismo día de la semana, o vacío si no existe.
     */
    @Transactional(readOnly = true)
    public Optional<DailyChoiceDto> findLastSameWeekdayOrder(Long userId) {
        LocalDate today = LocalDate.now(clock);
        // Java DayOfWeek: Mon=1..Sun=7. Postgres DOW: Sun=0..Sat=6. Mod 7 los matchea.
        int dow = today.getDayOfWeek().getValue() % 7;
        return orderRepo.findLastSameWeekdayBeforeToday(userId, dow)
            .map(DailyChoiceDto::from);
    }

    @Transactional(readOnly = true)
    public Optional<DishPreferenceDto> findDishPreference(Long userId, Long dishId) {
        return orderRepo.findFirstByUserIdAndDishIdOrderByFechaDesc(userId, dishId)
            .filter(o -> o.getSide() != null || (o.getNotas() != null && !o.getNotas().isBlank()))
            .map(DishPreferenceDto::from);
    }

    @Transactional(readOnly = true)
    public List<DailyChoiceDto> findWeekOrders(Long userId) {
        LocalDate today = LocalDate.now(clock);
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate end = monday.plusDays(13);
        return orderRepo.findByUserIdAndFechaBetweenOrderByFechaAsc(userId, monday, end)
            .stream().map(DailyChoiceDto::from).toList();
    }

    /**
     * Versión que mapea al DTO dentro de la transacción. Necesario porque
     * DailyChoiceDto accede a dish.enabled y side.enabled (lazy fetches) —
     * fuera de la transacción tira LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public Optional<DailyChoiceDto> findTodayOrderDto(Long userId) {
        return findTodayOrder(userId).map(DailyChoiceDto::from);
    }

    /** Wrapper que devuelve el DTO ya armado — usado por el controller. */
    @Transactional
    public DailyChoiceDto placeAndReturnDto(Long userId, PlaceOrderRequest req) {
        return DailyChoiceDto.from(place(userId, req));
    }

    /** Wrapper que devuelve el DTO ya armado — usado por el controller. */
    @Transactional
    public DailyChoiceDto updateAndReturnDto(Long userId, Long orderId, PlaceOrderRequest req) {
        return DailyChoiceDto.from(update(userId, orderId, req));
    }

    /**
     * Crea el pedido del día del empleado autenticado.
     * Valida + decrementa stock atómicamente + arma snapshots.
     */
    @Transactional
    public DailyChoice place(Long userId, PlaceOrderRequest req) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> BusinessException.notFound("user-not-found", "Usuario no encontrado"));

        if (user.getCompany() == null) {
            throw BusinessException.badRequest("no-company",
                "Solo usuarios con empresa asignada pueden hacer pedidos");
        }

        if (!Boolean.TRUE.equals(user.getCompany().getEnabled())) {
            throw BusinessException.forbidden("company-disabled",
                "Tu empresa fue desactivada. Contactá al administrador.");
        }

        LocalDate targetDate = req.fecha() != null ? req.fecha() : LocalDate.now(clock);
        boolean isFuture = targetDate.isAfter(LocalDate.now(clock));

        if (targetDate.isBefore(LocalDate.now(clock))) {
            throw BusinessException.badRequest("past-date", "No se puede pedir para días pasados");
        }

        if (fechaDeshabilitadaRepo.existsByFecha(targetDate)) {
            throw BusinessException.conflict("date-disabled",
                "El restaurant no recibe pedidos para esa fecha");
        }

        if (!isFuture) {
            ensureBeforeCutoff();
        }

        if (orderRepo.findByUserIdAndFecha(userId, targetDate).isPresent()) {
            throw BusinessException.conflict("order-already-exists",
                "Ya tenés un pedido para ese día");
        }

        Dish dish = dishRepo.findById(req.dishId())
            .orElseThrow(() -> BusinessException.notFound("dish-not-found", "Plato no encontrado"));

        if (!Boolean.TRUE.equals(dish.getEnabled())) {
            throw BusinessException.conflict("dish-disabled", "Ese plato no está disponible");
        }

        Side side = validateAndResolveSide(dish, req.sideId());

        if (!isFuture) {
            int updated = dishRepo.decrementStock(dish.getId());
            if (updated == 0) {
                throw BusinessException.conflict("out-of-stock", "Se agotó el stock de ese plato");
            }
        }

        DailyChoice order = DailyChoice.builder()
            .user(user)
            .company(user.getCompany())
            .fecha(targetDate)
            .dish(dish)
            .side(side)
            .notas(req.notas() != null && !req.notas().isBlank() ? req.notas().trim() : null)
            .estado(OrderEstado.PENDIENTE)
            .dishNombre(dish.getNombre())
            .dishCategoria(dish.getCategory().getNombre())
            .sideNombre(side != null ? side.getNombre() : null)
            .horaEntrega(user.getCompany().getHoraEntrega())
            .build();

        return orderRepo.save(order);
    }

    /** Edita el pedido — equivale a cancel + place pero en una transacción. */
    @Transactional
    public DailyChoice update(Long userId, Long orderId, PlaceOrderRequest req) {
        DailyChoice existing = mustOwn(userId, orderId);
        ensureModifiable(existing);
        ensureCompanyEnabled(existing.getUser());

        boolean isFuture = existing.getFecha().isAfter(LocalDate.now(clock));

        if (!isFuture) {
            ensureBeforeCutoff();
        }

        if (!existing.getDish().getId().equals(req.dishId())) {
            if (!isFuture) {
                dishRepo.incrementStock(existing.getDish().getId());
            }

            Dish newDish = dishRepo.findById(req.dishId())
                .orElseThrow(() -> BusinessException.notFound("dish-not-found", "Plato no encontrado"));
            if (!Boolean.TRUE.equals(newDish.getEnabled())) {
                throw BusinessException.conflict("dish-disabled", "Ese plato no está disponible");
            }

            if (!isFuture) {
                int updated = dishRepo.decrementStock(newDish.getId());
                if (updated == 0) {
                    dishRepo.incrementStock(existing.getDish().getId());
                    throw BusinessException.conflict("out-of-stock", "Se agotó el stock de ese plato");
                }
            }

            existing.setDish(newDish);
            existing.setDishNombre(newDish.getNombre());
            existing.setDishCategoria(newDish.getCategory().getNombre());
        }

        Side side = validateAndResolveSide(existing.getDish(), req.sideId());
        existing.setSide(side);
        existing.setSideNombre(side != null ? side.getNombre() : null);
        existing.setNotas(req.notas() != null && !req.notas().isBlank() ? req.notas().trim() : null);

        return orderRepo.save(existing);
    }

    /** Cancela el pedido y devuelve stock. */
    @Transactional
    public void cancel(Long userId, Long orderId) {
        DailyChoice existing = mustOwn(userId, orderId);
        ensureModifiable(existing);

        dishRepo.incrementStock(existing.getDish().getId());
        orderRepo.delete(existing);
    }

    /** Cancela el pedido del día sin necesidad de pasarle el id (atajo del frontend). */
    @Transactional
    public void cancelToday(Long userId) {
        cancelByDate(userId, LocalDate.now(clock));
    }

    @Transactional
    public void cancelByDate(Long userId, LocalDate fecha) {
        DailyChoice existing = orderRepo.findByUserIdAndFecha(userId, fecha)
            .orElseThrow(() -> BusinessException.notFound("order-not-found", "No tenés un pedido para ese día"));

        boolean isFuture = fecha.isAfter(LocalDate.now(clock));

        if (!isFuture) {
            ensureModifiable(existing);
            dishRepo.incrementStock(existing.getDish().getId());
        } else {
            if (existing.getEstado() != OrderEstado.PENDIENTE) {
                throw BusinessException.conflict("order-locked", "El pedido ya fue cerrado");
            }
        }

        orderRepo.delete(existing);
    }

    @Transactional
    public int markComandadoByCompany(Long companyId, LocalDate fecha) {
        return orderRepo.markComandadoByCompany(companyId, fecha, clock.instant());
    }

    @Transactional
    public int markDeliveredByCompany(Long companyId) {
        return orderRepo.markDeliveredByCompany(companyId, LocalDate.now(clock), clock.instant());
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private DailyChoice mustOwn(Long userId, Long orderId) {
        DailyChoice existing = orderRepo.findById(orderId)
            .orElseThrow(() -> BusinessException.notFound("order-not-found", "Pedido no encontrado"));
        if (!existing.getUser().getId().equals(userId)) {
            throw BusinessException.forbidden("order-not-owned", "No podés modificar pedidos de otros usuarios");
        }
        return existing;
    }

    private void ensureBeforeCutoff() {
        LocalTime cutoff = configRepo.getSingleton().getHoraCorte();
        if (!LocalTime.now(clock).isBefore(cutoff)) {
            throw BusinessException.conflict("order-cutoff-passed",
                "El horario de pedidos ya cerró");
        }
    }

    private void ensureModifiable(DailyChoice order) {
        if (order.getEstado() != OrderEstado.PENDIENTE) {
            throw BusinessException.conflict("order-locked",
                "El pedido ya fue cerrado por el corte — no se puede modificar");
        }
    }

    private void ensureCompanyEnabled(User user) {
        if (user.getCompany() != null
            && !Boolean.TRUE.equals(user.getCompany().getEnabled())) {
            throw BusinessException.forbidden("company-disabled",
                "Tu empresa fue desactivada. Contactá al administrador.");
        }
    }

    private Side validateAndResolveSide(Dish dish, Long sideId) {
        // sideId null = el empleado eligió explícitamente "sin acompañamiento/salsa".
        // Es válido tanto si el plato no admite sides como si los admite pero el
        // empleado prefiere el plato solo.
        if (sideId == null) {
            return null;
        }

        if (dish.getSideType() == null) {
            throw BusinessException.badRequest("side-not-allowed",
                "Este plato no lleva acompañamiento");
        }

        Side side = sideRepo.findById(sideId)
            .orElseThrow(() -> BusinessException.notFound("side-not-found", "Acompañamiento no encontrado"));

        // Defensa: el admin pudo haber desactivado el side después de asociarlo
        // a este plato. Rechazamos en backend aunque el frontend lo deje pasar.
        if (!Boolean.TRUE.equals(side.getEnabled())) {
            throw BusinessException.conflict("side-disabled",
                "Ese acompañamiento ya no está disponible");
        }

        if (side.getTipo() != dish.getSideType()) {
            throw BusinessException.badRequest("side-type-mismatch",
                "El tipo de acompañamiento no es el correcto para este plato");
        }

        boolean isAllowed = dish.getAllowedSides().stream()
            .anyMatch(s -> s.getId().equals(sideId));
        if (!isAllowed) {
            throw BusinessException.badRequest("side-not-in-allowed-list",
                "Ese acompañamiento no está permitido para este plato");
        }

        return side;
    }
}
