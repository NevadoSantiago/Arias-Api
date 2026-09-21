package com.arias.credits;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Movimiento inmutable del libro mayor de créditos. Append-only: una fila
 * nunca se actualiza ni se borra, solo se inserta — es la única fuente de
 * verdad auditable de todo cambio de saldo (spec {@code credit-ledger},
 * "Auditoría completa de movimientos").
 *
 * <p>{@code deltaAvailable}/{@code deltaCommitted} son los dos lados de la
 * partida doble: {@code wallet.available == SUM(deltaAvailable)} y
 * {@code wallet.committed == SUM(deltaCommitted)} son el invariante que
 * {@code CreditLedgerService} (unidad 3) verifica.
 *
 * <p>{@code orderId}/{@code purchaseId} son FKs lógicas — sin constraint de
 * base, porque {@code orders} (V18) y {@code credit_purchase} (V19) todavía
 * no existen cuando este movimiento puede necesitar referenciarlos (p. ej.
 * {@code WELCOME_GRANT} no referencia ninguno de los dos).
 */
@Entity
@Table(name = "credit_movement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private MovementType type;

    @Column(name = "delta_available", nullable = false)
    private Integer deltaAvailable;

    @Column(name = "delta_committed", nullable = false)
    private Integer deltaCommitted;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "purchase_id")
    private UUID purchaseId;

    @Column(name = "description", length = 255)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
