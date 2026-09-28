package com.arias.orders;

import com.arias.common.security.JwtUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints del pedido nuevo por créditos (unidad 7). Bajo {@code /api/v2}
 * para no colisionar con {@link OrderController} (camino {@code
 * DailyChoice}, sin cambios): ambos conviven mientras el frontend migra —
 * ver diseño §Decisión 2 y la nota de transición en {@code tasks.md} unidad
 * 7. Mismos roles que el camino viejo: los clientes B2C autorregistrados
 * también reciben {@code Role.EMPLOYEE} (con {@code company = NULL}), así
 * que no hace falta un rol nuevo.
 */
@RestController
@RequestMapping("/api/v2/orders")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('EMPLOYEE', 'COMPANY_ADMIN')")
public class OrderPlacementController {

    private final OrderPlacementService orderPlacementService;

    /**
     * "Mis pedidos" (gap fix) — pedidos del cliente autenticado, siempre
     * scopeados por {@code user.userId()}: nunca un parámetro de query, para
     * que sea imposible pedir los pedidos de otro usuario desde el cliente.
     * Ver {@link OrderPlacementService#list} para el orden y la cota.
     */
    @GetMapping
    public List<OrderDto> list(@AuthenticationPrincipal JwtUser user) {
        return orderPlacementService.list(user.userId());
    }

    @PostMapping
    public OrderDto place(
        @AuthenticationPrincipal JwtUser user,
        @Valid @RequestBody PlaceOrderV2Request req
    ) {
        return orderPlacementService.place(user.userId(), req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(
        @AuthenticationPrincipal JwtUser user,
        @PathVariable Long id
    ) {
        orderPlacementService.cancel(user.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Agrega ítems a un pedido existente mientras sea MODIFICABLE (unidad
     * B6, pedido del usuario 2026-09-27). Ver {@link
     * OrderPlacementService#addItems}.
     */
    @PostMapping("/{id}/items")
    public OrderDto addItems(
        @AuthenticationPrincipal JwtUser user,
        @PathVariable Long id,
        @Valid @RequestBody AddOrderItemsRequest req
    ) {
        return orderPlacementService.addItems(user.userId(), id, req);
    }

    /**
     * Quita un ítem de un pedido existente mientras sea MODIFICABLE — si era
     * el último, cancela el pedido entero (unidad B6). Ver {@link
     * OrderPlacementService#removeItem}.
     */
    @DeleteMapping("/{id}/items/{itemId}")
    public OrderDto removeItem(
        @AuthenticationPrincipal JwtUser user,
        @PathVariable Long id,
        @PathVariable Long itemId
    ) {
        return orderPlacementService.removeItem(user.userId(), id, itemId);
    }
}
