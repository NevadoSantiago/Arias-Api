package com.arias.users;

import com.arias.catalog.categories.Category;
import com.arias.companies.Company;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * User = todos los usuarios del sistema (3 roles).
 *
 * <p>Whitelist flow para EMPLOYEE:
 * <ol>
 *   <li>CompanyAdmin carga el email del empleado → se crea User con password_hash=NULL</li>
 *   <li>Empleado entra a la app, va a primer-login</li>
 *   <li>Setea nombre + apellido + password → password_hash se popula, first_login_at se registra</li>
 * </ol>
 *
 * <p>Para soft-disable usamos {@code active=false}, nunca hard-delete.
 * Esto preserva integridad referencial con DailyChoice/RefreshToken.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255, unique = true)
    private String email;

    /** NULL hasta que el empleado complete el first-login y setee su password. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    /** NULL para SUPER_ADMIN. NOT NULL para COMPANY_ADMIN y EMPLOYEE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    /** Solo relevante para EMPLOYEE — define qué platos puede ver. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false)
    @lombok.Builder.Default
    private Boolean active = true;

    /**
     * Opt-in al recordatorio diario por mail si no tiene pedido para hoy.
     * Default true al crear el user — el empleado lo puede apagar desde la app
     * o haciendo click en el link de unsubscribe del mismo mail.
     */
    @Column(name = "recibe_recordatorio_pedido", nullable = false)
    @lombok.Builder.Default
    private Boolean recibeRecordatorioPedido = true;

    /**
     * Soft delete. NULL = visible. Cuando se setea, el user desaparece
     * de listings, login y check-email — pero su id sigue referenciable
     * desde DailyChoice y otras tablas históricas.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "first_login_at")
    private Instant firstLoginAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
