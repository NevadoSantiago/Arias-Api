package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.sides.Side;
import com.arias.catalog.sides.SideRepository;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditLedgerService;
import com.arias.credits.MovementRef;
import com.arias.orders.notifications.OrderCancelledEvent;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
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
 *   <li>El horario de retiro se valida contra {@code restaurant_config}
 *       (unidad 8, migración V20) vía {@link PickupSlotService#assertValidPickupTime}:
 *       semana actual/siguiente, ventana de servicio y {@code
 *       pickup_lead_minutes} — mismo valor que usa {@link
 *       OrderConsumptionScheduler} para el punto de consumo automático.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderPlacementService {

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    /**
     * Cota de "mis pedidos" (gap fix, {@link #list}) — ver el javadoc de
     * {@link OrderRepository#findRecentByUserId} para por qué es un límite de
     * cantidad y no un rango de fechas.
     */
    private static final int RECENT_ORDERS_LIMIT = 30;

    private final OrderRepository orderRepo;
    private final UserRepository userRepo;
    private final DishRepository dishRepo;
    private final SideRepository sideRepo;
    private final CreditLedgerService creditLedgerService;
    private final PickupSlotService pickupSlotService;
    private final RestaurantConfigRepository restaurantConfigRepo;
    private final ApplicationEventPublisher eventPublisher;
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

        // Gap de la unidad 4/5: la verificación de correo se controla acá,
        // donde el usuario efectivamente gasta créditos — no en el login
        // (diseño §Seguridad, "Cuentas sin verificar"). Empleados de empresa
        // quedan exentos aunque emailVerifiedAt sea NULL (ver User.mustVerifyEmailToSpend).
        if (user.mustVerifyEmailToSpend()) {
            throw BusinessException.conflict("email-not-verified",
                "Debés verificar tu correo electrónico antes de pedir");
        }

        // Gap de la unidad 5/9 (diseño §Decisión 9): quien entró con Google
        // nunca dio teléfono ni apodo — sin eso la cocina no tiene a quién
        // nombrar ni a quién llamar. Va DESPUÉS del gate de email porque no
        // tiene sentido pedirle datos de perfil a una identidad todavía no
        // verificada. Empleados de empresa quedan exentos (ver
        // User.mustCompleteProfileToSpend).
        if (user.mustCompleteProfileToSpend()) {
            throw BusinessException.conflict("profile-incomplete",
                "Completá tu teléfono y apodo antes de pedir");
        }

        if (req.items() == null || req.items().isEmpty()) {
            throw BusinessException.badRequest("empty-order", "El pedido debe tener al menos un ítem");
        }

        if (req.pickupAt() == null) {
            throw BusinessException.badRequest("pickup-at-required", "Debe indicar el horario de retiro");
        }
        // Ventana de semana actual/siguiente, horario dentro del servicio y
        // tiempo mínimo de preparación — spec pickup-scheduling completa
        // (unidad 8), un solo punto de validación reutilizado también por
        // GET /api/v1/orders/pickup-slots.
        pickupSlotService.assertValidPickupTime(req.pickupAt());

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

        int lead = restaurantConfigRepo.getSingleton().getPickupLeadMinutes();
        return OrderDto.from(saved, isCancellable(saved, clock.instant(), lead));
    }

    /**
     * "Mis pedidos" (gap fix): pedidos del cliente autenticado, acotados y
     * ordenados por {@link OrderRepository#findRecentByUserId} — próximos
     * primero, luego los más recientes del pasado. Incluye pedidos
     * {@code CANCELADO} (soft-cancel, el cliente ve qué pasó con sus
     * créditos) a diferencia de {@link OrderRepository#findByFechaAndEstadoNot},
     * que los excluye para la cocina.
     *
     * <p>{@code cancellable} se calcula acá, no en el frontend: es
     * exactamente la misma regla que {@link #cancel}, y esa regla ya divergió
     * una vez entre frontend y backend en este proyecto.
     */
    @Transactional(readOnly = true)
    public List<OrderDto> list(Long userId) {
        Instant now = clock.instant();
        int lead = restaurantConfigRepo.getSingleton().getPickupLeadMinutes();

        return orderRepo.findRecentByUserId(userId, PageRequest.of(0, RECENT_ORDERS_LIMIT)).stream()
            .map(order -> OrderDto.from(order, isCancellable(order, now, lead)))
            .toList();
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
        int lead = restaurantConfigRepo.getSingleton().getPickupLeadMinutes();
        Instant deadline = order.getPickupAt().minus(lead, ChronoUnit.MINUTES);
        if (!now.isBefore(deadline)) {
            throw BusinessException.conflict("cancel-window-closed",
                "Ya no se puede cancelar: falta menos de " + lead
                    + " minutos para el retiro");
        }

        for (OrderItem item : order.getItems()) {
            dishRepo.incrementStock(item.getDish().getId());
        }

        creditLedgerService.release(userId, order.getCreditTotal(),
            MovementRef.forOrder(order.getId(), "Cancelación de pedido #" + order.getId()));

        order.setEstado(OrderEstado.CANCELADO);
        order.setCancelledAt(now);

        // Publicado DENTRO de la transacción — OrderNotificationScheduler lo
        // escucha con @TransactionalEventListener(phase = AFTER_COMMIT), así
        // que si esta transacción termina en rollback el mail nunca sale
        // (unidad 12, diseño §Decisión 11).
        User user = order.getUser();
        eventPublisher.publishEvent(new OrderCancelledEvent(
            order.getId(), userId, user.getEmail(), displayName(user),
            order.getPickupAt(), order.getCreditTotal()));
    }

    private static String displayName(User user) {
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            return user.getFirstName();
        }
        return user.getEmail();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    /**
     * Misma regla que {@link #cancel} sin lanzar: {@code PENDIENTE && now <
     * pickupAt - lead}. Único punto de verdad para "¿se puede cancelar ESTE
     * pedido AHORA?" — usado por {@link #place} y {@link #list} para que el
     * campo {@code cancellable} de {@link OrderDto} nunca se calcule dos
     * veces con lógica distinta.
     */
    private static boolean isCancellable(Order order, Instant now, int leadMinutes) {
        if (order.getEstado() != OrderEstado.PENDIENTE) {
            return false;
        }
        Instant deadline = order.getPickupAt().minus(leadMinutes, ChronoUnit.MINUTES);
        return now.isBefore(deadline);
    }

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
