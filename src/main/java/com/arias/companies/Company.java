package com.arias.companies;

import com.arias.catalog.categories.Category;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalTime;

/**
 * Company = empresa cliente del restaurant.
 * Tiene un horario de entrega propio (cuando van a almorzar sus empleados)
 * y una categoría default (los nuevos empleados se crean con esta).
 */
@Entity
@Table(name = "company")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String nombre;

    @Column(nullable = false, length = 13, unique = true)
    private String cuit;

    @Column(nullable = false, length = 200)
    private String calle;

    @Column(nullable = false, length = 20)
    private String altura;

    @Column(length = 20)
    private String piso;

    @Column(name = "hora_entrega", nullable = false)
    private LocalTime horaEntrega;

    /**
     * Categoría por defecto. Cuando el CompanyAdmin agrega un empleado,
     * se le asigna esta categoría como inicial. Puede sobrescribirse por empleado.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "categoria_default_id", nullable = false)
    private Category categoriaDefault;

    @Column(nullable = false)
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
