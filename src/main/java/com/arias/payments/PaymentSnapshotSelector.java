package com.arias.payments;

import java.util.List;

/**
 * Elige el pago que decide el estado de una compra cuando Mercado Pago
 * devuelve varios por la misma {@code external_reference}. Regla única,
 * compartida por la reconciliación horaria y la confirmación al volver del
 * checkout.
 */
final class PaymentSnapshotSelector {

    private PaymentSnapshotSelector() {
    }

    /**
     * Unidad B15.1: entre todos los pagos de la compra prefiere uno aprobado
     * (el reintento con otra tarjeta puede venir detrás de un rechazo, en
     * cualquier orden); si no hay ninguno aprobado, el más reciente. La
     * pasarela los devuelve del más reciente al más viejo. {@code null} si no
     * hay ningún pago.
     */
    static PaymentSnapshot best(List<PaymentSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        return snapshots.stream()
            .filter(s -> s.status() == PaymentStatus.APPROVED)
            .findFirst()
            .orElse(snapshots.get(0));
    }
}
