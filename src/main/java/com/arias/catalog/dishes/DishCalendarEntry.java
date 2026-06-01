package com.arias.catalog.dishes;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Asignación de un plato especial a una fecha concreta.
 * Reemplaza el modelo anterior de {@code dish_dia_semana} (recurrente por día de la semana).
 */
@Entity
@Table(name = "dish_calendario")
@IdClass(DishCalendarEntryId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DishCalendarEntry {

    @Id
    @Column(name = "dish_id")
    private Long dishId;

    @Id
    @Column(name = "fecha", nullable = false)
    private LocalDate fecha;
}
