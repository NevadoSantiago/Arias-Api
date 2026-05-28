package com.arias.catalog.menusections;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Sección del menú = agrupación visual (Carnes, Minutas, Pastas, ...).
 * Independiente del tier de acceso (Category) — es solo para UI.
 *
 * El empleado en el frontend ve estos como filter pills.
 * El SUPER_ADMIN puede crear/editar/desactivar secciones via CRUD.
 */
@Entity
@Table(name = "menu_section")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String nombre;

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
}
