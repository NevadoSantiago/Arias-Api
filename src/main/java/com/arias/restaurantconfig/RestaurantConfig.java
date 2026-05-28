package com.arias.restaurantconfig;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalTime;

/**
 * RestaurantConfig = singleton (siempre id=1).
 * Guarda la configuración global del restaurant.
 *
 * <p>La constraint en BD garantiza que solo puede haber UNA fila.
 */
@Entity
@Table(name = "restaurant_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RestaurantConfig {

    /** Siempre 1 — enforced por CHECK constraint en la BD. */
    @Id
    private Long id;

    /** Hora del día a la que se cierran los pedidos (corte). */
    @Column(name = "hora_corte", nullable = false)
    private LocalTime horaCorte;

    @Column(nullable = false, length = 50)
    private String timezone = "America/Argentina/Buenos_Aires";

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
