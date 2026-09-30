package com.arias.credits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Saldo materializado de créditos de un usuario. Una fila por usuario
 * ({@code user_id} es la PK, no un id generado) — ver diseño §Decisión 3.
 *
 * <p>{@code available} y {@code committed} son la proyección derivada del
 * libro mayor de {@link CreditMovement}; nunca se escriben sueltos, solo a
 * través de {@code CreditLedgerService.apply(...)} (unidad 3), que lee esta
 * fila con {@code SELECT ... FOR UPDATE} antes de mutarla.
 */
@Entity
@Table(name = "credit_wallet")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditWallet {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    @Builder.Default
    private Integer available = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer committed = 0;

    /**
     * Fecha de vencimiento global del saldo AVAILABLE (90 días por defecto,
     * configurable). NULL = sin saldo otorgado todavía. Solo se renueva por
     * compra de paquete, nunca por compra directa ni por reembolso.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Billetera vacía para un usuario que todavía no tiene fila — primer movimiento. */
    public static CreditWallet emptyFor(Long userId) {
        return CreditWallet.builder()
            .userId(userId)
            .available(0)
            .committed(0)
            .build();
    }
}
