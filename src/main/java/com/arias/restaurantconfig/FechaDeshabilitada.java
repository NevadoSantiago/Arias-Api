package com.arias.restaurantconfig;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "fecha_deshabilitada")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FechaDeshabilitada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate fecha;

    @Column(length = 200)
    private String motivo;
}
