package com.arias.credits.packs;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Paquete de créditos administrable por el {@code SUPER_ADMIN} (unidad 11,
 * diseño §Decisión 12) — día/semana/mes con descuento por volumen. Sigue la
 * convención de {@code MenuSection}/{@code Side}: entidad CRUD simple, soft
 * delete vía {@code deletedAt}.
 *
 * <p>{@code priceCents} es la ÚNICA fuente de verdad del dinero — nunca
 * punto flotante. {@code discountPercent} es metadato de presentación
 * (mostrarle al usuario "20% off"), nunca entra en el cálculo del cobro: el
 * cobro sale siempre de {@code priceCents} directamente.
 */
@Entity
@Table(name = "credit_pack")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditPack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** DAY | WEEK | MONTH — identificador estable, no editable desde el panel. */
    @Column(nullable = false, length = 20, unique = true)
    private String code;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(name = "credit_amount", nullable = false)
    private Integer creditAmount;

    /** Precio autoritativo en centavos de ARS. */
    @Column(name = "price_cents", nullable = false)
    private Long priceCents;

    /** Solo presentación — nunca entra en el cálculo del cobro. */
    @Column(name = "discount_percent", nullable = false)
    @Builder.Default
    private Integer discountPercent = 0;

    @Column(name = "orden_display", nullable = false)
    @Builder.Default
    private Integer ordenDisplay = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** Soft delete — un paquete borrado no se ofrece ni admite nuevas compras. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
