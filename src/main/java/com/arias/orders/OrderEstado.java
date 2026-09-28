package com.arias.orders;

/**
 * Estado compartido por {@link DailyChoice} (pedido de empresa, congelado) y
 * {@link Order} (pedido nuevo por créditos, unidad 7 — diseño §Decisión 4).
 *
 * Ciclo de vida:
 *   PENDIENTE_PAGO → SOLO Order (unidad B7): recién creado por {@code
 *                    /api/v2/orders/direct-checkout}, stock reservado, SIN
 *                    créditos comprometidos — esperando que Mercado Pago
 *                    apruebe la compra DIRECT asociada. Nunca aparece en
 *                    DailyChoice ni en ninguna vista de cocina/admin. Pasa a
 *                    PENDIENTE si el pago se aprueba, o a CANCELADO si se
 *                    rechaza/cancela/expira, si el cliente lo cancela, o si
 *                    llega el corte de retiro sin resolverse.
 *   PENDIENTE  → recién creado, créditos en COMMITTED (Order) o editable hasta el corte (DailyChoice)
 *   CONFIRMADO → créditos consumidos en pickup − lead (Order) o el cron de corte lo cerró (DailyChoice)
 *   COMANDADO  → el resto cargó el pedido en la comanda de cocina
 *   ENTREGADO  → el resto marcó que se entregó la comida
 *   CANCELADO  → soft-cancel de Order — nunca se usa sobre DailyChoice, que sigue
 *                cancelando con DELETE (diseño §Decisión 7: los movimientos del
 *                libro mayor referencian el pedido para siempre, así que Order
 *                nunca se borra)
 */
public enum OrderEstado {
    PENDIENTE_PAGO,
    PENDIENTE,
    CONFIRMADO,
    COMANDADO,
    ENTREGADO,
    CANCELADO
}
