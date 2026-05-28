package com.arias.catalog.dishes;

import com.arias.catalog.categories.Category;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.sides.Side;
import com.arias.catalog.sides.SideType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Dish = plato del menú.
 *
 * <p>Tiene dos dimensiones de clasificación independientes:
 * <ul>
 *   <li>{@link Category}: TIER de acceso (Premium/Básico) — uso interno, no se muestra al empleado</li>
 *   <li>{@link MenuSection}: SECCIÓN visual (Carnes/Minutas/Pastas/...) — usado para los filter pills</li>
 * </ul>
 *
 * <p>Si {@code sideType} es null, el plato NO lleva acompañamiento.
 * Si tiene un valor, los {@code allowedSides} deben ser todos de ese tipo.
 *
 * <p>Stock funciona en dos niveles:
 * <ul>
 *   <li>{@code stockDiarioDefault}: cantidad a la que se resetea cada mañana (cron)</li>
 *   <li>{@code stockActual}: cantidad disponible HOY, decrementa con cada pedido</li>
 * </ul>
 */
@Entity
@Table(name = "dish")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dish {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String nombre;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "foto_url", length = 500)
    private String fotoUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_section_id", nullable = false)
    private MenuSection menuSection;

    @Enumerated(EnumType.STRING)
    @Column(name = "side_type", length = 20)
    private SideType sideType;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean especial = false;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "dish_dia_semana", joinColumns = @JoinColumn(name = "dish_id"))
    @Column(name = "dia")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<DiaSemana> diasSemana = EnumSet.noneOf(DiaSemana.class);

    @Column(name = "stock_diario_default", nullable = false)
    private Integer stockDiarioDefault = 0;

    @Column(name = "stock_actual", nullable = false)
    private Integer stockActual = 0;

    /**
     * Sides permitidos para este plato. La relación se materializa en la pivot {@code dish_side}.
     * Lazy fetch: solo carga sides cuando se accede explícitamente.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "dish_side",
        joinColumns = @JoinColumn(name = "dish_id"),
        inverseJoinColumns = @JoinColumn(name = "side_id")
    )
    @Builder.Default
    private Set<Side> allowedSides = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
