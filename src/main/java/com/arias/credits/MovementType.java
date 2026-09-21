package com.arias.credits;

/**
 * Tipos de movimiento del libro mayor de créditos. Cada valor corresponde
 * exactamente a un valor del CHECK {@code chk_credit_movement_type} en
 * {@code credit_movement} (V17) — agregar un valor acá requiere una
 * migración que extienda ese CHECK.
 *
 * <p>Semántica de los deltas con signo por tipo (ver diseño §Decisión 3):
 * <ul>
 *   <li>{@link #WELCOME_GRANT}, {@link #PACK_PURCHASE} — {@code +N available}</li>
 *   <li>{@link #DIRECT_PURCHASE} — {@code +N committed}, sin pasar por available</li>
 *   <li>{@link #COMMIT} — {@code -N available, +N committed}</li>
 *   <li>{@link #RELEASE} — {@code +N available, -N committed}</li>
 *   <li>{@link #CONSUME} — {@code -N committed}</li>
 *   <li>{@link #EXPIRATION} — {@code -N available}, committed intacto</li>
 *   <li>{@link #PAYMENT_REVERSAL} — {@code -N available}, acotado a lo disponible</li>
 *   <li>{@link #ADMIN_ADJUSTMENT} — ambos deltas, cualquier signo (único tipo que puede dejar saldo negativo)</li>
 * </ul>
 */
public enum MovementType {
    WELCOME_GRANT,
    PACK_PURCHASE,
    DIRECT_PURCHASE,
    COMMIT,
    RELEASE,
    CONSUME,
    EXPIRATION,
    PAYMENT_REVERSAL,
    ADMIN_ADJUSTMENT
}
