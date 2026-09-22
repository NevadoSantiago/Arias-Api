package com.arias.payments;

/**
 * Estado de {@code CreditPurchase} — corresponde exactamente al CHECK
 * {@code chk_credit_purchase_status} de {@code credit_purchase} (V22, unidad
 * 11, diseño §Flujo de datos "Compra con Mercado Pago").
 */
public enum CreditPurchaseStatus {
    /** Creada, esperando el webhook o la reconciliación. */
    PENDING,
    /** Créditos ya acreditados. */
    APPROVED,
    /** Pago rechazado por Mercado Pago — nunca se acreditó. */
    REJECTED,
    /** Pago cancelado antes de completarse — nunca se acreditó. */
    CANCELLED,
    /** Los créditos otorgados (total o parcialmente) fueron revertidos por reembolso/contracargo. */
    REVERSED,
    /** Quedó PENDING más de 24 h sin que Mercado Pago reporte ningún pago para esa referencia. */
    EXPIRED,
    /** Disputa en mediación — no se acredita ni se revierte hasta resolución manual. */
    IN_MEDIATION
}
