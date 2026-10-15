package com.arias.orders;

import com.arias.restaurantconfig.RestaurantConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Job de consumo automático de créditos en el punto {@code pickup_at - lead}
 * — unidad 8, diseño §Decisión 4 ("consumo en pickup − lead = job programado
 * más re-validación perezosa"). Mismo patrón de cron que {@link
 * OrdersScheduler#closeOverdueOrders}: chequeo periódico cada minuto en vez
 * de un solo dispatch, así el sistema es robusto a downtime del server y a
 * cambios de {@code pickup_lead_minutes} en runtime.
 *
 * <p>Si el job está caído, nada se pierde: los créditos siguen en {@code
 * COMMITTED} y la re-validación perezosa de {@code
 * OrderPlacementService#cancel} sigue cerrando la ventana de cancelación de
 * forma determinística. Al volver, este job procesa TODO el atraso en el
 * primer tick porque la consulta es por {@code pickup_at}, no por "el minuto
 * actual".
 *
 * <p><b>Unidad B7</b>: un corte propio ({@code pickup_at - orderLead}, más una
 * gracia de {@link #UNPAID_GRACE_MINUTES} minutos desde la creación) cierra
 * los pedidos {@code PENDIENTE_PAGO} (esperando el pago directo de Mercado Pago) que todavía no
 * se resolvieron — "pago pendiente al momento de confirmar/preparar → se
 * cancela". Se cancelan, no se confirman: nunca comprometieron créditos, así
 * que no hay nada que consumir; si el pago se aprueba después de este cierre,
 * {@code CreditPurchaseService} lo detecta (pedido ya {@code CANCELADO}) y
 * acredita los almuerzos a {@code AVAILABLE} en vez de perderlos.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderConsumptionScheduler {

    /**
     * Gracia fija para pedidos {@code PENDIENTE_PAGO}: nunca se cierran antes
     * de {@code createdAt + 10 min}. Con un mínimo para pedir menor que el
     * tiempo de preparación, un pago directo creado cerca del retiro se
     * cancelaría al minuto siguiente sin dar tiempo a pagar.
     */
    static final int UNPAID_GRACE_MINUTES = 10;

    private final OrderRepository orderRepo;
    private final OrderKitchenService orderKitchenService;
    private final RestaurantConfigRepository configRepo;
    private final OrderPlacementService orderPlacementService;
    private final Clock clock;

    @Scheduled(cron = "30 * * * * *", zone = "America/Argentina/Buenos_Aires")
    @Transactional
    public void consumeDueOrders() {
        Instant now = clock.instant();
        var config = configRepo.getSingleton();
        // pickup_at - lead <= now  <=>  pickup_at <= now + lead
        Instant cutoff = now.plus(config.getPickupLeadMinutes(), ChronoUnit.MINUTES);

        List<Order> due = orderRepo.findByEstadoAndPickupAtLessThanEqual(OrderEstado.PENDIENTE, cutoff);
        for (Order order : due) {
            orderKitchenService.confirmOrder(order, now, "Consumo automático de pedido #" + order.getId());
        }

        // Pedidos esperando pago directo cuyo corte llegó sin que Mercado
        // Pago haya resuelto el pago todavía (unidad B7) — se cancelan por el
        // mismo cierre que un pago rechazado (closeAtCutoff): restaura
        // stock, libera solo lo reservado del saldo (B13) y, solo en
        // este camino, avisa al cliente por mail (unidad B8).
        // Corte propio: pickup_at - orderLead (el mínimo para pedir), y nunca
        // antes de la gracia desde que se creó el pedido.
        Instant unpaidCutoff = now.plus(config.getOrderLeadMinutes(), ChronoUnit.MINUTES);
        Instant graceLimit = now.minus(UNPAID_GRACE_MINUTES, ChronoUnit.MINUTES);
        List<Order> unpaidDue = orderRepo
            .findByEstadoAndPickupAtLessThanEqual(OrderEstado.PENDIENTE_PAGO, unpaidCutoff).stream()
            .filter(order -> !order.getCreatedAt().isAfter(graceLimit))
            .toList();
        for (Order order : unpaidDue) {
            orderPlacementService.closeAtCutoff(order.getId());
        }

        if (!due.isEmpty()) {
            log.info("[CRON] Consumo automático de créditos: {} pedidos confirmados", due.size());
        }
        if (!unpaidDue.isEmpty()) {
            log.info("[CRON] Cancelación automática de pedidos sin pagar: {} pedidos cancelados", unpaidDue.size());
        }
    }
}
