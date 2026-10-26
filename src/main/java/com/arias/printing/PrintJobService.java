package com.arias.printing;

import com.arias.catalog.dishes.DishStation;
import com.arias.common.exception.BusinessException;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderItem;
import com.arias.orders.OrderRepository;
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
    private final OrderRepository orderRepo;
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
                    .data(copies(renderer.render(order, station), copiesFor(station)))
                    .availableAt(availableAt)
                    .build()));
            }
        }
        return created;
    }

    /**
     * Reprints a COMANDADO or ENTREGADO order with the same routing, claimable right away. Idempotent
     * against double clicks and client retries: while the order still has PENDING or CLAIMED jobs
     * those are returned and nothing new is enqueued.
     */
    @Transactional
    public List<PrintJob> reprint(Long orderId) {
        Order order = orderRepo.findById(orderId)
            .orElseThrow(() -> BusinessException.notFound("order-not-found", "Pedido no encontrado"));
        if (order.getEstado() != OrderEstado.COMANDADO && order.getEstado() != OrderEstado.ENTREGADO) {
            throw BusinessException.conflict("order-not-printable",
                "Solo se pueden reimprimir pedidos comandados o entregados");
        }
        List<PrintJob> unfinished = jobRepo.findByOrderId(orderId).stream()
            .filter(j -> j.getStatus() == PrintJobStatus.PENDING || j.getStatus() == PrintJobStatus.CLAIMED)
            .toList();
        if (!unfinished.isEmpty()) {
            return unfinished;
        }
        return enqueue(List.of(order), clock.instant());
    }

    /** Cancels the jobs of the order that nobody claimed yet. Claimed or printed jobs stay as they are. */
    @Transactional
    public int cancelPending(Long orderId) {
        return jobRepo.cancelPendingForOrder(orderId, clock.instant());
    }

    /** Copies printed per ticket: the kitchen needs three, the grill one. */
    static int copiesFor(DishStation station) {
        return station == DishStation.COCINA ? 3 : 1;
    }

    /**
     * Repeats the ticket in one job, so the copies print together (no Maxirest ticket in between)
     * and share one print status. Each rendered ticket starts with a reset and ends with a cut.
     */
    static byte[] copies(byte[] ticket, int count) {
        byte[] data = new byte[ticket.length * count];
        for (int i = 0; i < count; i++) {
            System.arraycopy(ticket, 0, data, i * ticket.length, ticket.length);
        }
        return data;
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
