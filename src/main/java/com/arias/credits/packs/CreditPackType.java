package com.arias.credits.packs;

/**
 * Tipo de paquete de créditos. Reemplaza los códigos mágicos DAY/WEEK:
 * {@link #INDIVIDUAL} fija el precio por almuerzo del pago directo y
 * {@link #SUGERIDO} es el paquete que se sugiere a quien compra sueltos.
 * Como máximo existe un paquete vivo de cada uno; {@link #OTRO} no tiene
 * comportamiento especial.
 */
public enum CreditPackType {
    INDIVIDUAL,
    SUGERIDO,
    OTRO
}
