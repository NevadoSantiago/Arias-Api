package com.arias.payments;

/**
 * Estados de la Payments API clásica de Mercado Pago (no la Orders API, que es
 * un producto distinto con otro modelo de estados — diseño §Enfoque técnico,
 * investigación #607). Cualquier valor que Mercado Pago devuelva y que no
 * reconozcamos se mapea a {@link #UNKNOWN} en vez de lanzar: la API puede
 * agregar estados nuevos sin previo aviso y el webhook no debe romperse por eso.
 */
public enum PaymentStatus {
    APPROVED,
    PENDING,
    IN_PROCESS,
    AUTHORIZED,
    REJECTED,
    CANCELLED,
    REFUNDED,
    CHARGED_BACK,
    IN_MEDIATION,
    UNKNOWN;

    /**
     * Mapea el {@code status} crudo devuelto por {@code PaymentClient.get(id)}
     * (siempre en minúsculas y snake_case en la API real) al enum tipado.
     */
    public static PaymentStatus fromMercadoPago(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        return switch (raw.toLowerCase()) {
            case "approved" -> APPROVED;
            case "pending" -> PENDING;
            case "in_process" -> IN_PROCESS;
            case "authorized" -> AUTHORIZED;
            case "rejected" -> REJECTED;
            case "cancelled" -> CANCELLED;
            case "refunded" -> REFUNDED;
            case "charged_back" -> CHARGED_BACK;
            case "in_mediation" -> IN_MEDIATION;
            default -> UNKNOWN;
        };
    }
}
