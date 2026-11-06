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

    /** Admin edit: writes only the descriptive fields and the daily default, never {@code stock_actual}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Dessert d SET d.nombre = :nombre, d.descripcion = :descripcion, d.fotoUrl = :fotoUrl,
            d.stockDiarioDefault = :stockDiarioDefault, d.enabled = :enabled, d.updatedAt = CURRENT_TIMESTAMP
        WHERE d.id = :id
        """)
    int updateDetails(@Param("id") Long id, @Param("nombre") String nombre,
                      @Param("descripcion") String descripcion, @Param("fotoUrl") String fotoUrl,
                      @Param("stockDiarioDefault") int stockDiarioDefault, @Param("enabled") boolean enabled);

    /** Explicit stock correction by the admin (only when the request sends it). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Dessert d SET d.stockActual = :stock, d.updatedAt = CURRENT_TIMESTAMP WHERE d.id = :id")
    int updateStock(@Param("id") Long id, @Param("stock") int stock);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Dessert d SET d.enabled = :enabled, d.updatedAt = CURRENT_TIMESTAMP WHERE d.id = :id")
    int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
}
