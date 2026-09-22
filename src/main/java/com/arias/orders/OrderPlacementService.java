package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.sides.Side;
import com.arias.catalog.sides.SideRepository;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditLedgerService;
import com.arias.credits.MovementRef;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Lógica del pedido nuevo por créditos (unidad 7, diseño §Decisión 2 y §Decisión 4).
 *
 * <p><b>Camino ADITIVO, no reemplazo</b>: {@link OrderService} (sobre {@code
 * DailyChoice}) sigue existiendo sin cambios — sigue sirviendo al frontend
 * actual mientras dure la migración. Este service es el destino final para
 * TODO pedido nuevo, B2C o de empleado de empresa: en vez de {@code
 * CompanyCategoryPrice.precioSnapshot}, el costo sale de {@code
 * Category.creditCost} y se debita del saldo de créditos del usuario vía
 * {@link CreditLedgerService}. {@code order.company} es solo una instantánea
 * de {@code user.company} — nunca fuente de precio.
 *
 * <p>Reglas clave:
 * <ul>
 *   <li>Múltiples pedidos por día — sin UNIQUE(user_id, fecha) (spec
 *       {@code order-placement}).</li>
 *   <li>El total es la suma del {@code creditCost} de cada ítem.</li>
 *   <li>Stock se decrementa POR ÍTEM, atómicamente, ANTES de comprometer
 *       créditos — un ítem sin stock rechaza el pedido entero sin tocar el
 *       libro mayor (diagrama de flujo del diseño, sección "Pedido y
 *       consumo").</li>
 *   <li>Saldo insuficiente bloquea el pedido COMPLETO sin compromiso
 *       parcial: {@link CreditLedgerService#commit} corre dentro de la MISMA
 *       transacción que los decrementos de stock, así que si el commit falla
 *       Spring revierte también el stock ya decrementado.</li>
 *   <li>Cancelación: solo mientras {@code estado == PENDIENTE} y {@code now
 *       < pickupAt - lead}; libera créditos (RELEASE), restaura stock, y
 *       marca {@code CANCELADO} — nunca borra la fila, porque los
 *       movimientos del libro mayor la referencian para siempre.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderPlacementService {

    /**
     * Minutos de preparación por defecto, hasta que {@code
     * restaurant_config.pickup_lead_minutes} exista (unidad 8, migración
     * V20). Mismo patrón que {@code CreditLedgerService.DEFAULT_EXPIRY_DAYS}:
     * valor por defecto de diseño (20 minutos, ver V20) fijo hasta que la
     * unidad 8 lo vuelva configurable.
     */
    static final int DEFAULT_PICKUP_LEAD_MINUTES = 20;

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final OrderRepository orderRepo;
    private final UserRepository userRepo;
    private final DishRepository dishRepo;
    private final SideRepository sideRepo;
    private final CreditLedgerService creditLedgerService;
    private final Clock clock;

    /**
     * Crea un pedido nuevo. Decrementa stock por ítem y solo después
     * compromete créditos — en ese orden, para que un ítem agotado nunca
     * llegue a tocar el libro mayor.
     */
    @Transactional
    public OrderDto place(Long userId, PlaceOrderV2Request req) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> BusinessException.notFound("user-not-found", "Usuario no encontrado"));

        if (req.items() == null || req.items().isEmpty()) {
            throw BusinessException.badRequest("empty-order", "El pedido debe tener al menos un ítem");
        }

        Instant now = clock.instant();
        Instant earliest = now.plus(DEFAULT_PICKUP_LEAD_MINUTES, ChronoUnit.MINUTES);
        if (req.pickupAt() == null || req.pickupAt().isBefore(earliest)) {
            throw BusinessException.badRequest("pickup-too-soon",
                "El horario de retiro debe ser al menos " + DEFAULT_PICKUP_LEAD_MINUTES
                    + " minutos desde ahora");
        }

        List<OrderItem> items = new ArrayList<>();
        int total = 0;

        for (PlaceOrderV2Request.OrderItemRequest itemReq : req.items()) {
            Dish dish = dishRepo.findById(itemReq.dishId())
                .orElseThrow(() -> BusinessException.notFound("dish-not-found", "Plato no encontrado"));

            if (!Boolean.TRUE.equals(dish.getEnabled())) {
                throw BusinessException.conflict("dish-disabled", "Ese plato no está disponible");
            }

            Side side = validateAndResolveSide(dish, itemReq.sideId());

            // Decremento atómico ANTES de comprometer créditos — si se agotó,
            // el pedido entero se rechaza sin haber tocado el libro mayor.
            int updated = dishRepo.decrementStock(dish.getId());
            if (updated == 0) {
                throw BusinessException.conflict("out-of-stock",
                    "Se agotó el stock de " + dish.getNombre());
            }

            Category category = dish.getCategory();
            int creditCost = category.getCreditCost();
            total += creditCost;

            items.add(OrderItem.builder()
                .dish(dish)
                .side(side)
                .category(category)
                .dishNombre(dish.getNombre())
                .dishCategoria(category.getNombre())
                .sideNombre(side != null ? side.getNombre() : null)
                .creditCost(creditCost)
                .notas(trimOrNull(itemReq.notas()))
                .build());
        }

        Order order = Order.builder()
            .user(user)
            .company(user.getCompany())
            .fecha(LocalDate.ofInstant(req.pickupAt(), ZONE))
            .pickupAt(req.pickupAt())
            .estado(OrderEstado.PENDIENTE)
            .creditTotal(total)
            .notas(trimOrNull(req.notas()))
            .build();

        items.forEach(order::addItem);

        Order saved = orderRepo.save(order);

        // Si el saldo no alcanza, CreditLedgerService.commit lanza
        // insufficient-credits DENTRO de esta misma transacción — Spring
        // revierte también el save() del pedido y los decrementos de stock
        // de arriba. Ningún compromiso parcial es posible.
        creditLedgerService.commit(userId, total,
            MovementRef.forOrder(saved.getId(), "Pedido #" + saved.getId()));

        return OrderDto.from(saved);
    }

    /**
     * Cancela el pedido — solo mientras esté PENDIENTE y falte más de {@code
     * lead} minutos para el retiro (misma re-validación perezosa del diseño
     * §Decisión 4, independiente de si el job de consumo corrió).
     */
    @Transactional
    public void cancel(Long userId, Long orderId) {
        Order order = orderRepo.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> BusinessException.notFound("order-not-found", "Pedido no encontrado"));

        if (order.getEstado() != OrderEstado.PENDIENTE) {
            throw BusinessException.conflict("order-locked",
                "El pedido ya no se puede cancelar");
        }

        Instant now = clock.instant();
        Instant deadline = order.getPickupAt().minus(DEFAULT_PICKUP_LEAD_MINUTES, ChronoUnit.MINUTES);
        if (!now.isBefore(deadline)) {
            throw BusinessException.conflict("cancel-window-closed",
                "Ya no se puede cancelar: falta menos de " + DEFAULT_PICKUP_LEAD_MINUTES
                    + " minutos para el retiro");
        }

        for (OrderItem item : order.getItems()) {
            dishRepo.incrementStock(item.getDish().getId());
        }

        creditLedgerService.release(userId, order.getCreditTotal(),
            MovementRef.forOrder(order.getId(), "Cancelación de pedido #" + order.getId()));

        order.setEstado(OrderEstado.CANCELADO);
        order.setCancelledAt(now);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private Side validateAndResolveSide(Dish dish, Long sideId) {
        if (sideId == null) {
            return null;
        }

        if (dish.getSideType() == null) {
            throw BusinessException.badRequest("side-not-allowed",
                "Este plato no lleva acompañamiento");
        }

        Side side = sideRepo.findById(sideId)
            .orElseThrow(() -> BusinessException.notFound("side-not-found", "Acompañamiento no encontrado"));

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

    private static String trimOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }
}
