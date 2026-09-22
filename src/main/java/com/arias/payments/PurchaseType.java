package com.arias.payments;

/**
 * Tipo de {@code CreditPurchase} — corresponde exactamente al CHECK
 * {@code chk_credit_purchase_target} de {@code credit_purchase} (V22): PACK
 * exige {@code pack_id}, DIRECT exige {@code order_id} (unidad 11).
 */
public enum PurchaseType {
    PACK,
    DIRECT
}
