package com.arias.orders;

/**
 * Vista resumida de un pedido afectado por la desactivación de un plato o side.
 * Se muestra al admin en el modal de confirmación.
 */
public record AffectedOrderDto(
    Long orderId,
    String userFirstName,
    String userLastName,
    String userEmail,
    String companyName,
    String dishNombre,
    String sideNombre
) {
    public static AffectedOrderDto from(DailyChoice c) {
        var user = c.getUser();
        return new AffectedOrderDto(
            c.getId(),
            user.getFirstName(),
            user.getLastName(),
            user.getEmail(),
            c.getCompany().getNombre(),
            c.getDishNombre(),
            c.getSideNombre()
        );
    }
}
