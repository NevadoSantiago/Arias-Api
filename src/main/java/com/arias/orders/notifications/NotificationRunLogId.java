package com.arias.orders.notifications;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;

/** Clave compuesta de {@link NotificationRunLog} — {@code (tipo, fecha)}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class NotificationRunLogId implements Serializable {

    private String tipo;
    private LocalDate fecha;
}
