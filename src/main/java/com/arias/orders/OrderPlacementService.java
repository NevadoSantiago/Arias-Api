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
import com.arias.orders.notifications.OrderUnpaidCancelledEvent;
import com.arias.payments.CreditPurchaseRepository;
import com.arias.payments.PurchaseType;
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
import java.util.Set;
import java.util.function.BooleanSupplier;

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
    private final CreditPurchaseRepository creditPurchaseRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Crea un pedido nuevo. Decrementa stock por ítem y solo después
     * compromete créditos — en ese orden, para que un ítem agotado nunca
     * llegue a tocar el libro mayor.
     */
    @Transactional
    public OrderDto place(Long userId, PlaceOrderV2Request req) {
        User user = findUserAndAssertCanSpend(userId);
        Order order = buildValidatedOrder(user, req, OrderEstado.PENDIENTE);
        Order saved = orderRepo.save(order);

        // Si el saldo no alcanza, CreditLedgerService.commit lanza
        // insufficient-credits DENTRO de esta misma transacción — Spring
        // revierte también el save() del pedido y los decrementos de stock
        // de arriba. Ningún compromiso parcial es posible.
        creditLedgerService.commit(userId, saved.getCreditTotal(),
            MovementRef.forOrder(saved.getId(), "Pedido #" + saved.getId()));

        int lead = restaurantConfigRepo.getSingleton().getPickupLeadMinutes();
        return toDto(saved, clock.instant(), lead);
    }

    /**
     * Crea un pedido "esperando pago" (unidad B7, {@code POST
     * /api/v2/orders/direct-checkout}) — MISMA validación que {@link #place}
     * (gates de perfil, ítems, horario de retiro vía {@link
     * PickupSlotService#assertValidPickupTime}) y la MISMA reserva de stock
     * por ítem ({@link #buildItemAndReserveStock}), pero SIN comprometer
     * créditos: el pedido queda {@link OrderEstado#PENDIENTE_PAGO} hasta que
     * Mercado Pago apruebe el pago de la compra DIRECT asociada.
     *
     * <p>Se invoca DENTRO de la misma transacción que crea esa compra DIRECT
     * y el checkout de Mercado Pago ({@code
     * CreditPurchaseService#createDirectCheckout}) — un fallo en cualquier
     * paso posterior (incluida la llamada a Mercado Pago) revierte también
     * este pedido y el stock reservado, exactamente como {@link
     * com.arias.payments.CreditPurchaseService#createPurchase} revierte su
     * compra si el checkout falla.
     */
    @Transactional
    public Order placeAwaitingPayment(Long userId, PlaceOrderV2Request req) {
        User user = findUserAndAssertCanSpend(userId);
        Order order = buildValidatedOrder(user, req, OrderEstado.PENDIENTE_PAGO);
        // Sin creditLedgerService.commit a propósito — el pago de Mercado
        // Pago cubre el pedido entero, no el saldo de créditos del cliente.
        return orderRepo.save(order);
    }

    /**
     * "Mis pedidos" (gap fix): pedidos del cliente autenticado, acotados y
     * ordenados por {@link OrderRepository#findRecentByUserId} — próximos
     * primero, luego los más recientes del pasado. Incluye pedidos
     * {@code CANCELADO} y {@code PENDIENTE_PAGO} (soft-cancel/esperando pago,
     * el cliente necesita verlos para pagar o ver qué pasó con sus créditos)
     * a diferencia de {@link OrderRepository#findByFechaAndEstadoNotIn}, que
     * los excluye para la cocina.
     *
     * <p>{@code cancellable} se calcula acá, no en el frontend: es
     * exactamente la misma regla que {@link #cancel}, y esa regla ya divergió
     * una vez entre frontend y backend en este proyecto.
     */
    @Transactional(readOnly = true)
    public List<OrderDto> list(Long userId) {
        Instant now = clock.instant();
        int lead = restaurantConfigRepo.getSingleton().getPickupLeadMinutes();

        List<Order> orders = orderRepo.findRecentByUserId(userId, PageRequest.of(0, RECENT_ORDERS_LIMIT));

        // Una sola consulta para saber cuáles tienen compra DIRECT (evita un
        // existsBy por pedido). `modifiable` la necesita; el resto no.
        Set<Long> directOrderIds = orders.isEmpty()
            ? Set.of()
            : creditPurchaseRepo.findOrderIdsWithPurchaseType(
                orders.stream().map(Order::getId).toList(), PurchaseType.DIRECT);

        return orders.stream()
            .map(order -> toDto(order, now, lead, () -> directOrderIds.contains(order.getId())))
            .toList();
    }

    /**
     * Cancela el pedido — solo mientras esté PENDIENTE o {@code
     * PENDIENTE_PAGO} (unidad B7: un pedido esperando pago también se puede
     * cancelar, sin liberar créditos porque nunca se comprometieron — ver
     * {@link #applyCancellation}) y falte más de {@code lead} minutos para el
     * retiro (misma re-validación perezosa del diseño §Decisión 4,
     * independiente de si el job de consumo corrió).
     */
    @Transactional
    public void cancel(Long userId, Long orderId) {
        Order order = orderRepo.findByIdAndUserIdForUpdate(orderId, userId)
            .orElseThrow(() -> BusinessException.notFound("order-not-found", "Pedido no encontrado"));

        if (order.getEstado() != OrderEstado.PENDIENTE && order.getEstado() != OrderEstado.PENDIENTE_PAGO) {
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

        applyCancellation(order, now);
    }

    /**
     * Agrega ítems a un pedido existente (unidad B6, pedido del usuario
     * 2026-09-27) — mientras el pedido sea MODIFICABLE, misma regla exacta
     * que {@link #isCancellable}. Reutiliza {@link #buildItemAndReserveStock}
     * — la MISMA validación de plato/guarnición/stock que {@link #place} — y
     * comprometé los créditos de los ítems agregados con el mismo movimiento
     * ({@code COMMIT}) que un pedido nuevo: si el saldo no alcanza, {@link
     * CreditLedgerService#commit} lanza {@code insufficient-credits} dentro de
     * la MISMA transacción, revirtiendo también el stock ya decrementado —
     * ningún compromiso parcial es posible, igual que en {@link #place}.
     */
    @Transactional
    public OrderDto addItems(Long userId, Long orderId, AddOrderItemsRequest req) {
        Order order = orderRepo.findByIdAndUserIdForUpdate(orderId, userId)
            .orElseThrow(() -> BusinessException.notFound("order-not-found", "Pedido no encontrado"));

        Instant now = clock.instant();
        int lead = restaurantConfigRepo.getSingleton().getPickupLeadMinutes();
        assertModifiable(order, now, lead);

        if (req.items() == null || req.items().isEmpty()) {
            throw BusinessException.badRequest("empty-order", "Debe agregar al menos un ítem");
        }

        int added = 0;
        for (PlaceOrderV2Request.OrderItemRequest itemReq : req.items()) {
            OrderItem item = buildItemAndReserveStock(itemReq);
            added += item.getCreditCost();
            order.addItem(item);
        }

        order.setCreditTotal(order.getCreditTotal() + added);
        Order saved = orderRepo.save(order);

        creditLedgerService.commit(userId, added,
            MovementRef.forOrder(orderId, "Ítems agregados al pedido #" + orderId));

        return toDto(saved, now, lead);
    }

    /**
     * Cambia el horario de retiro de un pedido (unidad B11, pedido del
     * usuario 2026-09-28). Solo un pedido programado ({@code PENDIENTE}) y
     * ANTES del corte del horario ACTUAL ({@code pickupAt - lead}, ver {@link
     * #isPickupTimeChangeable}); confirmado, esperando pago, cancelado o ya
     * cortado responde 409 {@code pickup-time-locked}. Un pedido pagado
     * aparte (DIRECT) SÍ puede cambiarlo: el importe no varía.
     *
     * <p>El horario nuevo debe caer el MISMO día (fecha de Buenos Aires) —
     * otro día es otro menú y otro stock — y pasa por {@link
     * PickupSlotService#assertValidPickupTime}: rango, día cerrado, ventana
     * del día de la semana, alineación y lead. Igual al actual es un no-op.
     * Actualiza {@code pickupAt} y {@code fecha} (misma derivación que {@link
     * #buildValidatedOrder}) y limpia {@code reminderSentAt} para que el
     * recordatorio salga para el horario nuevo. Stock y créditos no cambian.
     * Pasar a un horario donde ya hay otro pedido es válido: siguen siendo
     * pedidos separados, no hay fusión.
     *
     * <p>Toma el lock de la fila del pedido (mismo orden de locks que {@link
     * #addItems}/{@link #cancel}, unidad B7.1): la aprobación de pago y el
     * corte del scheduler también tocan pedidos.
     */
    @Transactional
    public OrderDto changePickupTime(Long userId, Long orderId, Instant newPickupAt) {
        if (newPickupAt == null) {
            throw BusinessException.badRequest("pickup-at-required", "Debe indicar el horario de retiro");
        }
        Order order = orderRepo.findByIdAndUserIdForUpdate(orderId, userId)
            .orElseThrow(() -> BusinessException.notFound("order-not-found", "Pedido no encontrado"));

        Instant now = clock.instant();
        int lead = restaurantConfigRepo.getSingleton().getPickupLeadMinutes();
        if (!isPickupTimeChangeable(order, now, lead)) {
            throw BusinessException.conflict("pickup-time-locked",
                "El horario de retiro ya no se puede cambiar.");
        }

        if (newPickupAt.equals(order.getPickupAt())) {
            return toDto(order, now, lead);
        }

        if (!LocalDate.ofInstant(newPickupAt, ZONE).equals(order.getFecha())) {
            throw BusinessException.conflict("pickup-day-change-not-allowed",
                "Solo podés cambiar el horario dentro del mismo día.");
        }
        pickupSlotService.assertValidPickupTime(newPickupAt);

        order.setPickupAt(newPickupAt);
        order.setFecha(LocalDate.ofInstant(newPickupAt, ZONE));
        order.setReminderSentAt(null);
        Order saved = orderRepo.save(order);

        return toDto(saved, now, lead);
    }

    /**
     * Quita un ítem de un pedido existente (unidad B6) — mientras el pedido
     * sea MODIFICABLE, misma regla que {@link #isCancellable}. Libera los
     * créditos comprometidos de ESE ítem (RELEASE) y restaura su stock — el
     * MISMO movimiento que hace {@link #applyCancellation} para el pedido
     * completo, pero acotado al costo de un solo ítem.
     *
     * <p>Si era el ÚLTIMO ítem, quitarlo equivale a cancelar el pedido
     * entero: se delega directamente en {@link #applyCancellation} (mismo
     * cierre, mismos eventos/mails que {@link #cancel}) en vez de liberar el
     * ítem y además cancelar por separado — así el crédito del pedido se
     * libera EXACTAMENTE una vez.
     */
    @Transactional
    public OrderDto removeItem(Long userId, Long orderId, Long itemId) {
        Order order = orderRepo.findByIdAndUserIdForUpdate(orderId, userId)
            .orElseThrow(() -> BusinessException.notFound("order-not-found", "Pedido no encontrado"));

        Instant now = clock.instant();
        int lead = restaurantConfigRepo.getSingleton().getPickupLeadMinutes();
        assertModifiable(order, now, lead);

        OrderItem item = order.getItems().stream()
            .filter(i -> i.getId().equals(itemId))
            .findFirst()
            .orElseThrow(() -> BusinessException.notFound("order-item-not-found", "Ítem no encontrado"));

        if (order.getItems().size() == 1) {
            applyCancellation(order, now);
        } else {
            dishRepo.incrementStock(item.getDish().getId());
            creditLedgerService.release(userId, item.getCreditCost(),
                MovementRef.forOrder(orderId, "Ítem removido del pedido #" + orderId));
            order.getItems().remove(item);
            order.setCreditTotal(order.getCreditTotal() - item.getCreditCost());
        }

        return toDto(order, now, lead);
    }

    /**
     * Cierra un pedido "esperando pago" (unidad B7, {@link
     * OrderEstado#PENDIENTE_PAGO}) cuyo pago DIRECT terminó sin pagarse
     * (rechazado, cancelado, o expirado por {@code
     * PaymentReconciliationScheduler} — diseño §Flujo de datos, tabla "Estado
     * del pago → Acción": "si era compra directa, se cancela el pedido
     * asociado"), o cuyo punto de consumo (pickup − lead) llegó sin que el
     * pago se haya resuelto todavía ({@code OrderConsumptionScheduler} —
     * bullet "pago pendiente al momento de confirmar/preparar → se cancela").
     * Invocado por {@code CreditPurchaseService} y por {@code
     * OrderConsumptionScheduler}, nunca directamente desde un controller.
     *
     * <p>Reutiliza el MISMO cierre que {@link #cancel} — restaurar stock,
     * marcar {@code CANCELADO} — vía {@link #applyCancellation}, que NUNCA
     * libera créditos para un pedido {@code PENDIENTE_PAGO} porque {@link
     * #placeAwaitingPayment} nunca los comprometió, y A PROPÓSITO sin la
     * ventana de cancelación del cliente ({@code now < pickupAt - lead}): esa
     * ventana existe para que un cliente no cancele "a último momento" un
     * pedido que SÍ iba a cocinarse, no para este caso — acá el pedido de
     * todos modos NUNCA se va a pagar a tiempo. Por eso no puede reusarse
     * {@link #cancel} tal cual: exige {@code userId} (ownership del cliente,
     * no aplica a un cierre disparado por el sistema) y ese deadline (que acá
     * no debe aplicar).
     *
     * <p><b>Idempotente</b>: si el pedido ya no está {@code PENDIENTE_PAGO}
     * (ya se cerró por un webhook/reconciliación/scheduler anterior, el pago
     * ya se aprobó, o el cliente ya lo canceló), es un no-op — así un webhook
     * duplicado, una segunda pasada de reconciliación, o un tick del
     * scheduler que corre después de que el pago se resolvió, nunca liberan
     * stock dos veces ni pisan un pedido que ya está en otro estado.
     */
    @Transactional
    public void closeForPaymentFailure(Long orderId) {
        closeAwaitingPayment(orderId, false);
    }

    /**
     * Cierre de un pedido {@link OrderEstado#PENDIENTE_PAGO} en el corte
     * ({@code pickup_at - lead}) — unidad B8. Igual que {@link
     * #closeForPaymentFailure} (mismo lock, misma idempotencia, mismo cierre
     * de stock) y, además, avisa al cliente por mail que el pago no se
     * registró a tiempo y que, si se acredita después, los almuerzos quedan
     * disponibles. Invocado SOLO por {@code OrderConsumptionScheduler}: el
     * rechazo/expiración del pago y la cancelación del cliente no mandan este
     * mail porque el cliente ya lo ve en la app.
     */
    @Transactional
    public void closeAtCutoff(Long orderId) {
        closeAwaitingPayment(orderId, true);
    }

    private void closeAwaitingPayment(Long orderId, boolean notifyCustomer) {
        // Relee bajo lock (unidad B7.1, hallazgo de revisión): el llamador
        // (OrderConsumptionScheduler) carga el pedido SIN lock antes de
        // invocar esto, y una aprobación de pago concurrente puede moverlo a
        // PENDIENTE entre esa lectura y esta llamada — si ya ganó la
        // carrera, el estado fresco ya no es PENDIENTE_PAGO y este método es
        // un no-op, en vez de cancelar un pedido que la aprobación ya activó.
        Order order = orderRepo.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getEstado() != OrderEstado.PENDIENTE_PAGO) {
            return;
        }
        applyCancellation(order, clock.instant());

        if (notifyCustomer) {
            // Publicado DENTRO de la transacción; el mail sale AFTER_COMMIT
            // (OrderNotificationScheduler#onUnpaidOrderCancelled).
            User user = order.getUser();
            eventPublisher.publishEvent(new OrderUnpaidCancelledEvent(
                order.getId(), user.getId(), user.getEmail(), displayName(user), order.getPickupAt()));
        }
    }

    /**
     * Núcleo compartido de {@link #cancel} y {@link #closeForPaymentFailure}:
     * restaura stock, marca {@code CANCELADO} y, SOLO si el pedido tenía
     * créditos comprometidos ({@code estado == PENDIENTE} — nunca para
     * {@code PENDIENTE_PAGO}, unidad B7, cuyos créditos jamás se
     * comprometieron), libera esos créditos COMMITTED y publica el evento de
     * cancelación (su copy de cliente asume créditos liberados — para
     * {@code PENDIENTE_PAGO} no aplica y no se publica). El llamador es
     * responsable de cualquier validación previa (ventana de cancelación,
     * ownership, idempotencia) — acá se asume que YA se decidió que el
     * pedido debe cerrarse.
     */
    private void applyCancellation(Order order, Instant now) {
        Long userId = order.getUser().getId();
        boolean teniaCreditosComprometidos = order.getEstado() == OrderEstado.PENDIENTE;

        for (OrderItem item : order.getItems()) {
            dishRepo.incrementStock(item.getDish().getId());
        }

        if (teniaCreditosComprometidos) {
            creditLedgerService.release(userId, order.getCreditTotal(),
                MovementRef.forOrder(order.getId(), "Cancelación de pedido #" + order.getId()));
        }

        order.setEstado(OrderEstado.CANCELADO);
        order.setCancelledAt(now);

        if (teniaCreditosComprometidos) {
            // Publicado DENTRO de la transacción — OrderNotificationScheduler
            // lo escucha con @TransactionalEventListener(phase = AFTER_COMMIT),
            // así que si esta transacción termina en rollback el mail nunca
            // sale (unidad 12, diseño §Decisión 11).
            User user = order.getUser();
            eventPublisher.publishEvent(new OrderCancelledEvent(
                order.getId(), userId, user.getEmail(), displayName(user),
                order.getPickupAt(), order.getCreditTotal()));
        }
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
     * Misma regla que {@link #cancel} sin lanzar: {@code (PENDIENTE ||
     * PENDIENTE_PAGO) && now < pickupAt - lead}. Único punto de verdad para
     * "¿se puede cancelar ESTE pedido AHORA?" — usado por {@link #place} y
     * {@link #list} para que el campo {@code cancellable} de {@link
     * OrderDto} nunca se calcule dos veces con lógica distinta. {@code
     * PENDIENTE_PAGO} (unidad B7) es cancelable con la MISMA ventana — pero
     * NO es MODIFICABLE, ver {@link #assertModifiable}.
     */
    private static boolean isCancellable(Order order, Instant now, int leadMinutes) {
        if (order.getEstado() != OrderEstado.PENDIENTE && order.getEstado() != OrderEstado.PENDIENTE_PAGO) {
            return false;
        }
        Instant deadline = order.getPickupAt().minus(leadMinutes, ChronoUnit.MINUTES);
        return now.isBefore(deadline);
    }

    /**
     * Un pedido es MODIFICABLE exactamente cuando es cancelable — decisión de
     * usuario 2026-09-27 (unidad B6): misma regla ({@link #isCancellable}),
     * mismo par de razones subyacentes (estado distinto de {@code PENDIENTE},
     * o dentro de {@code lead} minutos del retiro), pero un solo código de
     * error para ambas — a diferencia de {@link #cancel}, que distingue
     * {@code order-locked} de {@code cancel-window-closed}.
     *
     * <p><b>Pedidos pagados por compra DIRECTA</b> (unidad B6.1, hallazgo de
     * revisión): {@code addItems}/{@code removeItem} mueven {@code
     * available}↔{@code committed} de la billetera, exactamente como un
     * pedido pagado con saldo propio — pero una compra {@code DIRECT}
     * acredita {@code DIRECT_PURCHASE} derecho a {@code committed}, sin pasar
     * por {@code available} (ver {@code CreditPurchaseService#creditPurchase}).
     * Si se dejara modificar un pedido así, el {@code creditTotal} que queda
     * después del cambio ya no coincidiría con el importe que la compra
     * DIRECT cobró (o va a cobrar) por Mercado Pago — el pago queda atado al
     * total ORIGINAL del pedido, no al que resulte de agregar/quitar ítems.
     * Se rechaza con el MISMO código {@code order-not-modifiable} de arriba
     * (mismo contrato para el frontend), pero un mensaje que aclara el motivo
     * real en vez de mezclarlo con "ya no se puede cancelar".
     *
     * <p><b>Pedidos esperando pago</b> (unidad B7): un pedido {@code
     * PENDIENTE_PAGO} es CANCELABLE ({@link #isCancellable} lo acepta) pero
     * nunca MODIFICABLE — agregar/quitar ítems movería {@code creditTotal},
     * que ya es el importe exacto que la compra DIRECT le cobra (o le va a
     * cobrar) al cliente por Mercado Pago. Chequeo explícito ANTES de {@link
     * #isCancellable} en vez de depender de que después falle el chequeo de
     * {@code existsByOrderIdAndType} de abajo, porque ESE chequeo es sobre
     * compras ya asociadas — un pedido recién creado por {@code
     * placeAwaitingPayment} puede no tener su compra DIRECT todavía
     * persistida en el instante exacto en que se evalúa esto.
     */
    private void assertModifiable(Order order, Instant now, int leadMinutes) {
        String reason = notModifiableReason(order, now, leadMinutes,
            () -> creditPurchaseRepo.existsByOrderIdAndType(order.getId(), PurchaseType.DIRECT));
        if (reason != null) {
            throw BusinessException.conflict("order-not-modifiable", reason);
        }
    }

    /**
     * Único punto de verdad de "¿a este pedido se le pueden agregar/quitar
     * ítems AHORA?" (unidad B10): lo usan {@link #assertModifiable} (que lanza
     * con el motivo) y el campo {@code modifiable} de {@link OrderDto} (que
     * solo mira si hay motivo), así ambos nunca pueden divergir. Devuelve el
     * mensaje para el cliente, o {@code null} si el pedido es modificable.
     *
     * <p>El orden de los chequeos es el de siempre (estado {@code
     * PENDIENTE_PAGO}, ventana, compra DIRECT) y la consulta de la compra
     * DIRECT es perezosa ({@code hasDirectPurchase}): el listado la resuelve
     * en bloque, sin una consulta por pedido, y un pedido ya no modificable
     * por estado/ventana ni la evalúa.
     */
    private static String notModifiableReason(Order order, Instant now, int leadMinutes,
                                              BooleanSupplier hasDirectPurchase) {
        if (order.getEstado() == OrderEstado.PENDIENTE_PAGO) {
            return "Este pedido está esperando el pago y no se puede modificar.";
        }
        if (!isCancellable(order, now, leadMinutes)) {
            return "El pedido ya no se puede modificar";
        }
        if (hasDirectPurchase.getAsBoolean()) {
            return "Este pedido se pagó aparte y no se puede modificar.";
        }
        return null;
    }

    /**
     * ¿Se puede cambiar el horario de retiro AHORA? (unidad B11) — pedido
     * programado ({@code PENDIENTE}) y antes del corte {@code pickupAt -
     * lead}. A diferencia de {@link #isCancellable}, excluye {@code
     * PENDIENTE_PAGO} (no cambia de horario); a diferencia de {@link
     * #notModifiableReason}, un pedido pagado aparte (DIRECT) SÍ puede
     * cambiarlo porque el importe no varía.
     */
    private static boolean isPickupTimeChangeable(Order order, Instant now, int leadMinutes) {
        return order.getEstado() == OrderEstado.PENDIENTE && isCancellable(order, now, leadMinutes);
    }

    /** DTO de un solo pedido: la compra DIRECT se consulta solo si hace falta. */
    private OrderDto toDto(Order order, Instant now, int leadMinutes) {
        return toDto(order, now, leadMinutes,
            () -> creditPurchaseRepo.existsByOrderIdAndType(order.getId(), PurchaseType.DIRECT));
    }

    private static OrderDto toDto(Order order, Instant now, int leadMinutes, BooleanSupplier hasDirectPurchase) {
        return OrderDto.from(order,
            isCancellable(order, now, leadMinutes),
            notModifiableReason(order, now, leadMinutes, hasDirectPurchase) == null,
            isPickupTimeChangeable(order, now, leadMinutes));
    }

    /**
     * Carga el usuario y aplica los dos gates de "puede gastar" (email
     * verificado, perfil completo) — compartido por {@link #place} y {@link
     * #placeAwaitingPayment} para que ambos caminos nunca diverjan en esta
     * validación. Ver el javadoc original de cada gate en el historial de
     * {@link #place} (unidades 4/5 y 5/9).
     */
    private User findUserAndAssertCanSpend(Long userId) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> BusinessException.notFound("user-not-found", "Usuario no encontrado"));

        if (user.mustVerifyEmailToSpend()) {
            throw BusinessException.conflict("email-not-verified",
                "Debés verificar tu correo electrónico antes de pedir");
        }
        if (user.mustCompleteProfileToSpend()) {
            throw BusinessException.conflict("profile-incomplete",
                "Completá tu teléfono y apodo antes de pedir");
        }
        return user;
    }

    /**
     * Valida el request exactamente como el {@link #place} original (ítems no
     * vacíos, horario de retiro requerido y válido vía {@link
     * PickupSlotService#assertValidPickupTime}), reserva el stock de cada
     * ítem ({@link #buildItemAndReserveStock}) y arma — SIN persistir — el
     * {@link Order} resultante en el {@code estado} pedido. Compartido por
     * {@link #place} ({@code PENDIENTE}) y {@link #placeAwaitingPayment}
     * ({@code PENDIENTE_PAGO}, unidad B7) para que ambos caminos nunca
     * diverjan en esta validación; lo único que cambia entre los dos es si el
     * llamador compromete créditos después.
     */
    private Order buildValidatedOrder(User user, PlaceOrderV2Request req, OrderEstado estado) {
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
            OrderItem item = buildItemAndReserveStock(itemReq);
            total += item.getCreditCost();
            items.add(item);
        }

        Order order = Order.builder()
            .user(user)
            .company(user.getCompany())
            .fecha(LocalDate.ofInstant(req.pickupAt(), ZONE))
            .pickupAt(req.pickupAt())
            .estado(estado)
            .creditTotal(total)
            .notas(trimOrNull(req.notas()))
            .build();

        items.forEach(order::addItem);
        return order;
    }

    /**
     * Valida un ítem exactamente como {@link #place} (plato habilitado,
     * guarnición permitida) y reserva su stock atómicamente ANTES de que el
     * llamador comprometa créditos — compartido por {@link #place} y {@link
     * #addItems} para que ambos caminos nunca diverjan en esta validación.
     */
    private OrderItem buildItemAndReserveStock(PlaceOrderV2Request.OrderItemRequest itemReq) {
        Dish dish = dishRepo.findById(itemReq.dishId())
            .orElseThrow(() -> BusinessException.notFound("dish-not-found", "Plato no encontrado"));

        if (!Boolean.TRUE.equals(dish.getEnabled())) {
            throw BusinessException.conflict("dish-disabled", "Ese plato no está disponible");
        }

        Side side = validateAndResolveSide(dish, itemReq.sideId());

        // Decremento atómico ANTES de comprometer créditos — si se agotó, el
        // pedido/agregado entero se rechaza sin haber tocado el libro mayor.
        int updated = dishRepo.decrementStock(dish.getId());
        if (updated == 0) {
            throw BusinessException.conflict("out-of-stock",
                "Se agotó el stock de " + dish.getNombre());
        }

        Category category = dish.getCategory();
        int creditCost = category.getCreditCost();

        return OrderItem.builder()
            .dish(dish)
            .side(side)
            .category(category)
            .dishNombre(dish.getNombre())
            .dishCategoria(category.getNombre())
            .sideNombre(side != null ? side.getNombre() : null)
            .creditCost(creditCost)
            .notas(trimOrNull(itemReq.notas()))
            .build();
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
