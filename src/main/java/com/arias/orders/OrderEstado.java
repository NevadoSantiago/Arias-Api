package com.arias.orders;

/**
 * Estado del DailyChoice (pedido del empleado).
 *
 * Ciclo de vida:
 *   PENDIENTE  → recién creado por el empleado, editable hasta el corte
 *   CONFIRMADO → el cron de corte lo cerró, el resto ya lo recibió
 *   COMANDADO  → el resto cargó el pedido en la comanda de cocina
 *   ENTREGADO  → el resto marcó que se entregó la comida a la empresa
 */
public enum OrderEstado {
    PENDIENTE,
    CONFIRMADO,
    COMANDADO,
    ENTREGADO
}
