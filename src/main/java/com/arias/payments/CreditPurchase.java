package com.arias.payments;

import com.arias.credits.packs.CreditPack;
import com.arias.orders.Order;
import com.arias.users.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Compra de créditos (paquete o directa) mediante Mercado Pago Checkout Pro
 * (unidad 11, diseño §Modelo de datos, migración V22). El {@code id} es el
 * {@code external_reference} que viaja en la preferencia de pago — se genera
 * en la aplicación (no autogenerado por la base) para poder usarlo ANTES de
 * persistir la preferencia.
 *
 * <p>{@code mpPaymentId} lleva la {@code UNIQUE} de la base — es la
 * enforcement real de la idempotencia del webhook, no un {@code if} de
 * aplicación (diseño §Seguridad, "Replay"). {@code creditsReversed} acumula
 * cuánto de {@code creditAmount} ya se revirtió, para que un reembolso
 * parcial encadenado nunca revierta de más (ver {@code
 * MercadoPagoWebhookController}, decisión del orquestador que resuelve la
 * unidad 9).
 */
@Entity
@Table(name = "credit_purchase")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditPurchase {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PurchaseType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pack_id")
    private CreditPack pack;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "credit_amount", nullable = false)
    private Integer creditAmount;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "ARS";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreditPurchaseStatus status;

    @Column(name = "mp_preference_id", length = 100)
    private String mpPreferenceId;

    /** UNIQUE en la base — la enforcement real de "no acreditar dos veces". */
    @Column(name = "mp_payment_id", length = 50)
    private String mpPaymentId;

    @Column(name = "mp_status_detail", length = 100)
    private String mpStatusDetail;

    /** Créditos ya revertidos acumulados — nunca supera {@code creditAmount}. */
    @Column(name = "credits_reversed", nullable = false)
    @Builder.Default
    private Integer creditsReversed = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "credited_at")
    private Instant creditedAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;
}
