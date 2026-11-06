package com.arias.catalog.desserts;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DessertRepository extends JpaRepository<Dessert, Long> {

    /** Postres no borrados (incluye deshabilitados), para el panel admin. */
    List<Dessert> findByDeletedAtIsNullOrderByNombreAsc();

    /** Postres habilitados y no borrados, para el cliente (con o sin stock). */
    List<Dessert> findByEnabledTrueAndDeletedAtIsNullOrderByNombreAsc();

    /** Decremento atómico: devuelve 1 si había stock, 0 si no. */
    @Modifying
    @Query("UPDATE Dessert d SET d.stockActual = d.stockActual - 1 WHERE d.id = :id AND d.stockActual > 0")
    int decrementStock(@Param("id") Long id);

    /** Devolución de stock (por ejemplo al cancelar un pedido con postre). */
    @Modifying
    @Query("UPDATE Dessert d SET d.stockActual = d.stockActual + 1 WHERE d.id = :id")
    int incrementStock(@Param("id") Long id);

    /** Reset diario del stock (lo dispara el cron a las 00:00). */
    @Modifying
    @Query("UPDATE Dessert d SET d.stockActual = d.stockDiarioDefault WHERE d.enabled = true AND d.deletedAt IS NULL")
    int resetAllStock();
}
