package com.arias.orders;

import com.arias.credits.CreditLedgerService;
import com.arias.credits.MovementRef;
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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderConsumptionScheduler {

    private final OrderRepository orderRepo;
    private final CreditLedgerService creditLedgerService;
    private final RestaurantConfigRepository configRepo;
    private final Clock clock;

    @Scheduled(cron = "30 * * * * *", zone = "America/Argentina/Buenos_Aires")
    @Transactional
    public void consumeDueOrders() {
        Instant now = clock.instant();
        int lead = configRepo.getSingleton().getPickupLeadMinutes();
        // pickup_at - lead <= now  <=>  pickup_at <= now + lead
        Instant cutoff = now.plus(lead, ChronoUnit.MINUTES);

        List<Order> due = orderRepo.findByEstadoAndPickupAtLessThanEqual(OrderEstado.PENDIENTE, cutoff);
        for (Order order : due) {
            creditLedgerService.consume(order.getUser().getId(), order.getCreditTotal(),
                MovementRef.forOrder(order.getId(), "Consumo automático de pedido #" + order.getId()));
            order.setEstado(OrderEstado.CONFIRMADO);
            order.setConfirmedAt(now);
        }

        if (!due.isEmpty()) {
            log.info("[CRON] Consumo automático de créditos: {} pedidos confirmados", due.size());
        }
    }
}
