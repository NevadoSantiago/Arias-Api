package com.arias.orders;

import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditLedgerService;
import com.arias.credits.MovementRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Transiciones de estado de los {@link Order} B2C: PENDIENTE -> CONFIRMADO ->
 * COMANDADO -> ENTREGADO, con deshacer de un paso (salvo la confirmación). Batches are all-or-nothing: every order is
 * validated before any is changed, and the whole call runs in one transaction.
 */
@Service
@RequiredArgsConstructor
public class OrderKitchenService {

    private final OrderRepository orderRepository;
    private final CreditLedgerService creditLedgerService;
    private final Clock clock;

    /**
     * PENDIENTE -> CONFIRMADO a mano. Hace lo mismo que el cron en {@code
     * pickup - preparación}: consume los créditos comprometidos y registra
     * {@code confirmedAt}. No tiene deshacer porque los créditos ya se
     * consumieron. Todo o nada: se valida cada pedido antes de tocar alguno.
     */
    @Transactional
    public List<Order> markConfirmado(List<Long> ids) {
        List<Order> orders = loadForUpdate(ids);
        orders.forEach(o -> requireEstado(o, OrderEstado.PENDIENTE));
        Instant now = clock.instant();
        orders.forEach(o -> confirmOrder(o, now, "Consumo de pedido #" + o.getId() + " — confirmado desde el tablero"));
        return orders;
    }

    /**
     * Transición única PENDIENTE -> CONFIRMADO, compartida con el cron de
     * consumo: consume los créditos, marca CONFIRMADO y fija {@code confirmedAt}.
     * No valida el estado; quien llama ya filtró los pedidos PENDIENTE.
     */
    void confirmOrder(Order order, Instant now, String movementDescription) {
        creditLedgerService.consume(order.getUser().getId(), order.getCreditTotal(),
            MovementRef.forOrder(order.getId(), movementDescription));
        order.setEstado(OrderEstado.CONFIRMADO);
        order.setConfirmedAt(now);
    }

    @Transactional
    public List<Order> markComandado(List<Long> ids) {
        List<Order> orders = loadForUpdate(ids);
        orders.forEach(o -> requireEstado(o, OrderEstado.CONFIRMADO));
        Instant now = clock.instant();
        orders.forEach(o -> {
            o.setEstado(OrderEstado.COMANDADO);
            o.setComandadoAt(now);
        });
        return orders;
    }

    @Transactional
    public List<Order> markEntregado(List<Long> ids) {
        List<Order> orders = loadForUpdate(ids);
        orders.forEach(o -> requireEstado(o, OrderEstado.COMANDADO));
        Instant now = clock.instant();
        orders.forEach(o -> {
            o.setEstado(OrderEstado.ENTREGADO);
            o.setDeliveredAt(now);
        });
        return orders;
    }

    /** COMANDADO -> CONFIRMADO (clears comandadoAt); ENTREGADO -> COMANDADO (clears deliveredAt). */
    @Transactional
    public Order undo(Long id) {
        Order order = loadForUpdate(List.of(id)).get(0);
        switch (order.getEstado()) {
            case COMANDADO -> {
                order.setEstado(OrderEstado.CONFIRMADO);
                order.setComandadoAt(null);
            }
            case ENTREGADO -> {
                order.setEstado(OrderEstado.COMANDADO);
                order.setDeliveredAt(null);
            }
            default -> throw invalidTransition(order);
        }
        return order;
    }

    /** Loads the distinct orders, in request order, locking the rows; 400 if empty, 404 if any is missing. */
    private List<Order> loadForUpdate(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw BusinessException.badRequest("order-ids-required", "Se requiere al menos un pedido");
        }
        List<Long> distinct = List.copyOf(new LinkedHashSet<>(ids));
        Map<Long, Order> byId = orderRepository.findAllByIdInForUpdate(distinct).stream()
            .collect(Collectors.toMap(Order::getId, Function.identity()));
        return distinct.stream()
            .map(id -> {
                Order o = byId.get(id);
                if (o == null) {
                    throw BusinessException.notFound("order-not-found", "Pedido no encontrado: " + id);
                }
                return o;
            })
            .toList();
    }

    private void requireEstado(Order order, OrderEstado expected) {
        if (order.getEstado() != expected) {
            throw invalidTransition(order);
        }
    }

    private BusinessException invalidTransition(Order order) {
        return BusinessException.conflict("order-invalid-transition",
            "El pedido " + order.getId() + " esta en estado " + order.getEstado()
                + " y no admite este cambio");
    }
}
