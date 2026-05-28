package com.arias.orders;

import com.arias.catalog.dishes.Dish;
import com.arias.catalog.sides.Side;
import com.arias.companies.Company;
import com.arias.users.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DailyChoice = pedido del empleado del día.
 *
 * <p>Reglas críticas:
 * <ul>
 *   <li>UNIQUE (user_id, fecha) — un empleado, UN pedido por día</li>
 *   <li>Los campos {@code dishNombre/dishCategoria/sideNombre/horaEntrega} son SNAPSHOTS:
 *       una vez confirmado, NO se actualizan aunque cambien las entidades referenciadas.
 *       Esto preserva la historia para reportes y auditoría.</li>
 *   <li>Edición/cancelación solo permitida mientras {@code estado == PENDIENTE}
 *       Y antes de la hora_corte del RestaurantConfig.</li>
 * </ul>
 */
@Entity
@Table(
    name = "daily_choice",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_daily_choice_user_fecha",
        columnNames = {"user_id", "fecha"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyChoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false)
    private LocalDate fecha;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_id", nullable = false)
    private Dish dish;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "side_id")
    private Side side;

    @Column(columnDefinition = "TEXT")
    private String notas;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderEstado estado;

    // ── Snapshots inmutables ──────────────────────────────────────────
    @Column(name = "dish_nombre", nullable = false, length = 150)
    private String dishNombre;

    @Column(name = "dish_categoria", nullable = false, length = 100)
    private String dishCategoria;

    @Column(name = "side_nombre", length = 100)
    private String sideNombre;

    @Column(name = "hora_entrega", nullable = false)
    private LocalTime horaEntrega;

    // ── Timestamps ─────────────────────────────────────────────────────
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "comandado_at")
    private Instant comandadoAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;
}
