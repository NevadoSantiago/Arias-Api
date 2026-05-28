package com.arias.restaurantconfig;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FechaDeshabilitadaRepository extends JpaRepository<FechaDeshabilitada, Long> {
    boolean existsByFecha(LocalDate fecha);
    Optional<FechaDeshabilitada> findByFecha(LocalDate fecha);
    List<FechaDeshabilitada> findByFechaBetweenOrderByFechaAsc(LocalDate from, LocalDate to);
    List<FechaDeshabilitada> findByFechaGreaterThanEqualOrderByFechaAsc(LocalDate from);
    void deleteByFecha(LocalDate fecha);
}
