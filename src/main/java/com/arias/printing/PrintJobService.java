package com.arias.printing;

import com.arias.catalog.dishes.DishStation;
import com.arias.orders.Order;
import com.arias.orders.OrderItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Enqueues the kitchen tickets of an order and cancels the pending ones.
 *
 * <p>Routing: a COCINA ticket is ALWAYS printed; a PARRILLA ticket is also printed when the order
 * has at least one PARRILLA dish. Both carry the full order.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrintJobService {

    /** Delay between comandar and the jobs becoming claimable (room for the undo window). */
    public static final Duration COMANDA_DELAY = Duration.ofSeconds(10);

    private final PrintJobRepository jobRepo;
    private final PrinterRepository printerRepo;
    private final TicketRenderer renderer;
    private final Clock clock;

    /** Enqueues the tickets of freshly comandado orders, claimable after {@link #COMANDA_DELAY}. */
    @Transactional
    public List<PrintJob> enqueueForComandado(Collection<Order> orders) {
        return enqueue(orders, clock.instant().plus(COMANDA_DELAY));
    }

    @Transactional
    public List<PrintJob> enqueue(Collection<Order> orders, Instant availableAt) {
        List<PrintJob> created = new ArrayList<>();
        for (Order order : orders) {
            for (DishStation station : stationsFor(order)) {
                Printer printer = printerRepo.findById(station).orElse(null);
                if (printer == null) {
                    // Never block comandar because a printer row is missing; surface it in the logs.
                    log.error("No printer configured for station {}; skipping ticket of order {}",
                        station, order.getId());
                    continue;
                }
                created.add(jobRepo.save(PrintJob.builder()
                    .order(order)
                    .station(station)
                    .host(printer.getHost())
                    .port(printer.getPort())
                    .data(renderer.render(order, station))
                    .availableAt(availableAt)
                    .build()));
            }
        }
        return created;
    }

    /** Cancels the jobs of the order that nobody claimed yet. Claimed or printed jobs stay as they are. */
    @Transactional
    public int cancelPending(Long orderId) {
        return jobRepo.cancelPendingForOrder(orderId, clock.instant());
    }

    static List<DishStation> stationsFor(Order order) {
        boolean parrilla = order.getItems().stream()
            .map(OrderItem::getDish)
            .anyMatch(d -> d != null && d.getStation() == DishStation.PARRILLA);
        return parrilla
            ? List.of(DishStation.COCINA, DishStation.PARRILLA)
            : List.of(DishStation.COCINA);
    }
}
