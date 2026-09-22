package com.arias.orders;

import com.arias.users.User;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * DTO enriquecido para la vista del SUPER_ADMIN — incluye nombre del empleado
 * y empresa para que el resto vea quién pidió qué.
 */
public record AdminOrderDto(
    Long id,
    LocalDate fecha,
    OrderEstado estado,

    // Datos del que pidió
    Long userId,
    String userFirstName,
    String userLastName,
    String userEmail,

    // Empresa (snapshot del nombre no, vamos vivo porque el admin quiere el actual)
    Long companyId,
    String companyName,

    // Pedido (snapshots)
    Long dishId,
    String dishNombre,
    String dishCategoria,
    String sideNombre,
    String notas,
    LocalTime horaEntrega
) {
    public static AdminOrderDto from(DailyChoice c) {
        var user = c.getUser();
        var company = c.getCompany();
        return new AdminOrderDto(
            c.getId(),
            c.getFecha(),
            c.getEstado(),
            user.getId(),
            user.getFirstName(),
            user.getLastName(),
            user.getEmail(),
            company.getId(),
            company.getNombre(),
            c.getDish().getId(),
            c.getDishNombre(),
            c.getDishCategoria(),
            c.getSideNombre(),
            c.getNotas(),
            c.getHoraEntrega()
        );
    }

    // ── Vista agrupada por horario de retiro (unidad 13, spec `admin-order-fulfillment`) ──
    //
    // A diferencia de DailyChoice (un pedido = un ítem, de ahí que AdminOrderDto
    // sea plano), un Order tiene varios OrderItem, así que la fila de la cocina
    // necesita su propia lista de ítems. Se agrega junto a la vista por empresa
    // de arriba, sin tocarla: ambas leen fuentes distintas (`daily_choice` vs
    // `orders`) y se sirven desde endpoints separados en AdminOrderController.

    /** Ítem dentro de un pedido agrupado por horario de retiro — plato + acompañamiento. */
    public record PickupOrderItemDto(String dishNombre, String sideNombre, Integer creditCost) {
        public static PickupOrderItemDto from(OrderItem item) {
            return new PickupOrderItemDto(item.getDishNombre(), item.getSideNombre(), item.getCreditCost());
        }
    }

    /**
     * Un pedido dentro de la agrupación por horario de retiro — legible por
     * cocina: apodo del cliente (capturado en el autorregistro, ver {@code
     * User.nickname}), sus ítems y sus notas. Cae a nombre y apellido si el
     * usuario no tiene apodo (caso del empleado de empresa, que no pasa por
     * el autorregistro B2C).
     */
    public record PickupOrderDto(Long id, String customerNickname, List<PickupOrderItemDto> items, String notas) {
        public static PickupOrderDto from(Order order) {
            return new PickupOrderDto(
                order.getId(),
                customerLabel(order.getUser()),
                order.getItems().stream().map(PickupOrderItemDto::from).toList(),
                order.getNotas()
            );
        }

        private static String customerLabel(User user) {
            String nickname = user.getNickname();
            if (nickname != null && !nickname.isBlank()) {
                return nickname;
            }
            String first = user.getFirstName();
            String last = user.getLastName();
            String name = "";
            if (first != null) name += first;
            if (last != null) name += (name.isEmpty() ? "" : " ") + last;
            return name.isBlank() ? user.getEmail() : name.trim();
        }
    }

    /** Un horario de retiro con todos los pedidos que caen en él, ordenados por horario ascendente. */
    public record PickupGroupDto(LocalTime pickupTime, List<PickupOrderDto> orders) {
        public static List<PickupGroupDto> groupByPickup(List<Order> orders, ZoneId zone) {
            Map<LocalTime, List<PickupOrderDto>> byPickupTime = orders.stream()
                .collect(Collectors.groupingBy(
                    o -> LocalTime.ofInstant(o.getPickupAt(), zone),
                    TreeMap::new,
                    Collectors.mapping(PickupOrderDto::from, Collectors.toList())
                ));
            return byPickupTime.entrySet().stream()
                .map(e -> new PickupGroupDto(e.getKey(), e.getValue()))
                .toList();
        }
    }
}
