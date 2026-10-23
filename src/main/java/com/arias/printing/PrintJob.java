package com.arias.printing;

import com.arias.catalog.dishes.DishStation;
import com.arias.orders.Order;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * One ticket to print. {@code data} holds the ready-to-send ESC/POS bytes; {@code host}/{@code port}
 * are copied from the {@link Printer} when the job is enqueued, so a later printer edit does not
 * redirect jobs that are already queued.
 */
@Entity
@Table(name = "print_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrintJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DishStation station;

    @Column(nullable = false, length = 45)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(nullable = false)
    private byte[] data;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PrintJobStatus status = PrintJobStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    /** Random token of the current claim; the agent must echo it in the ack. Regenerated on every (re-)claim. */
    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "printed_at")
    private Instant printedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
