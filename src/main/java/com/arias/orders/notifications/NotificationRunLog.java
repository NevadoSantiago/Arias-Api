package com.arias.orders.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Log de envíos de notificaciones del ciclo del pedido (unidad 12) — una
 * fila por {@code (tipo, fecha)}, mismo dedup atómico por PK que {@code
 * ReminderRunLog}: sirve para el resumen matutino, que es una vez por día.
 *
 * <p>El recordatorio de retiro NO usa esta tabla — es por pedido individual
 * (varios pedidos comparten fecha con horarios de retiro distintos), así que
 * su dedup vive en {@code Order.reminderSentAt} (ver migración V21).
 */
@Entity
@Table(name = "notification_run_log")
@IdClass(NotificationRunLogId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRunLog {

    @Id
    @Column(name = "tipo", nullable = false, length = 30)
    private String tipo;

    @Id
    @Column(name = "fecha", nullable = false)
    private LocalDate fecha;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "recipients", nullable = false)
    private Integer recipients;
}
