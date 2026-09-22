package com.arias.orders;

/**
 * Estado compartido por {@link DailyChoice} (pedido de empresa, congelado) y
 * {@link Order} (pedido nuevo por créditos, unidad 7 — diseño §Decisión 4).
 *
 * Ciclo de vida:
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
    PENDIENTE,
    CONFIRMADO,
    COMANDADO,
    ENTREGADO,
    CANCELADO
}
