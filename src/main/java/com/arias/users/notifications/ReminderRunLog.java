package com.arias.users.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una fila por día — sirve como dedup atómico para que el cron no mande
 * mails dos veces el mismo día (race del cron, reinicio del server, etc.).
 */
@Entity
@Table(name = "reminder_run_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReminderRunLog {

    @Id
    @Column(name = "fecha", nullable = false)
    private LocalDate fecha;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "recipients", nullable = false)
    private Integer recipients;
}
