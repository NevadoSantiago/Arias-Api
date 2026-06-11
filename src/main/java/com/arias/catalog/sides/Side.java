package com.arias.catalog.sides;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Side = guarnición o salsa.
 * El tipo está discriminado por la columna {@code tipo} (GUARNICION | SALSA).
 *
 * Un Dish que lleva acompañamiento define el {@link SideType} permitido,
 * y solo se pueden asociar Sides de ese tipo (validación en service layer).
 */
@Entity
@Table(name = "side")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Side {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SideType tipo;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** Soft delete — NULL = visible. Mismo patrón que Dish/Category. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
