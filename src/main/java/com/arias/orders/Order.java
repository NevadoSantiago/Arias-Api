package com.arias.orders;

import com.arias.companies.Company;
import com.arias.users.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Order = pedido nuevo por créditos (unidad 7, diseño §Decisión 2).
 *
 * <p>Convive con {@link DailyChoice} en vez de reemplazarla: {@code
 * daily_choice} queda congelada como historia de solo lectura, y TODO pedido
 * nuevo — B2C o empleado de empresa — nace acá y consume créditos vía {@code
 * CreditLedgerService} (unidad 3), nunca {@code CompanyCategoryPrice}.
 *
 * <p>Reglas clave:
 * <ul>
 *   <li>Sin UNIQUE(user_id, fecha) — a diferencia de {@code daily_choice}, un
 *       usuario puede tener varios pedidos el mismo día.</li>
 *   <li>{@code creditTotal} es la suma del {@code creditCost} de cada {@link
 *       OrderItem} — confirmarlo COMMITea esa cantidad de créditos.</li>
 *   <li>{@code companyId} es una instantánea NULLABLE copiada de {@code
 *       user.company} al confirmar — nunca fuente de precio, solo agrupación
 *       para los endpoints del admin.</li>
 *   <li>La cancelación es SOFT ({@code estado = CANCELADO}, {@code
 *       cancelledAt}), nunca {@code DELETE}: los movimientos del libro mayor
 *       referencian el pedido para siempre (diseño §Decisión 7).</li>
 * </ul>
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Instantánea de {@code user.company} al confirmar — NULL para B2C. Nunca fuente de precio. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(name = "pickup_at", nullable = false)
    private Instant pickupAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderEstado estado;

    /** Suma del {@code creditCost} de cada {@link OrderItem} — lo que COMMITea el libro mayor. */
    @Column(name = "credit_total", nullable = false)
    private Integer creditTotal;

    @Column(columnDefinition = "TEXT")
    private String notas;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

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

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * Dedup atómico del recordatorio de retiro (unidad 12, migración V21):
     * {@code NULL} hasta que {@code OrderNotificationScheduler} lo reclama
     * vía {@code UPDATE ... WHERE reminder_sent_at IS NULL}, mismo patrón que
     * {@code dishRepo.decrementStock}. Por pedido, no por día — a diferencia
     * del resumen matutino, que sí dedupea con {@code notification_run_log}.
     */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    /** Asocia el ítem a este pedido y lo agrega a la colección — mantiene ambos lados de la relación. */
    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }
}
