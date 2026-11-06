package com.arias.catalog.desserts;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Postre: catálogo propio, usado solo como premio del programa de fidelización.
 * No aparece en el menú ni tiene costo en almuerzos/créditos.
 *
 * <p>Stock en dos niveles, igual que {@code Dish}: {@code stockDiarioDefault}
 * (valor al que resetea el cron de medianoche) y {@code stockActual} (disponible hoy).
 */
@Entity
@Table(name = "dessert")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dessert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String nombre;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "foto_url", length = 500)
    private String fotoUrl;

    @Column(name = "stock_diario_default", nullable = false)
    @Builder.Default
    private Integer stockDiarioDefault = 0;

    @Column(name = "stock_actual", nullable = false)
    @Builder.Default
    private Integer stockActual = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Soft delete. NULL = visible. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
