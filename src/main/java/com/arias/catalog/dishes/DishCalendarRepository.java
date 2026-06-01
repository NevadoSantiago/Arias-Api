package com.arias.catalog.dishes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DishCalendarRepository extends JpaRepository<DishCalendarEntry, DishCalendarEntryId> {

    List<DishCalendarEntry> findByFecha(LocalDate fecha);

    List<DishCalendarEntry> findByFechaBetween(LocalDate from, LocalDate to);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM DishCalendarEntry e WHERE e.fecha = :fecha")
    void deleteByFecha(@Param("fecha") LocalDate fecha);
}
