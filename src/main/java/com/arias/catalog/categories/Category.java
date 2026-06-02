package com.arias.catalog.categories;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Categoría = tier de acceso del usuario (Premium, Básico, Pescado).
 *
 * Se modela como árbol mediante {@code parentId} (self-reference):
 * un usuario con categoría X puede ver platos de X + todos sus descendientes.
 *
 * Ejemplo: si "Básico" tiene parent="Premium", entonces un usuario Premium
 * ve sus platos Premium + los Básicos. Pero un usuario Básico solo ve Básico.
 */
@Entity
@Table(name = "category")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String nombre;

    /**
     * FK a otra Category. Si null, esta categoría es raíz del árbol.
     * Usamos lazy fetch para evitar cargar todo el árbol cada vez.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(name = "orden_display", nullable = false)
    private Integer ordenDisplay = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft delete. NULL = visible. Cuando se setea, la categoría desaparece
     * de listados, dropdowns y queries de visibilidad — pero los registros
     * que la referencian (dish, user, company_category_price) siguen apuntando.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
