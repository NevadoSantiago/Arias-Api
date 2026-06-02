package com.arias.catalog.dishes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface DishRepository extends JpaRepository<Dish, Long> {

    /**
     * Platos disponibles para un usuario, dado el conjunto de categorías visibles
     * (resuelto previamente desde la categoría del usuario + descendientes).
     * Solo retorna platos enabled con stock > 0.
     *
     * <p>Los platos especiales solo aparecen si tienen una asignación en
     * {@link DishCalendarEntry} para la fecha en cuestión.
     */
    @Query("""
        SELECT DISTINCT d FROM Dish d
        LEFT JOIN FETCH d.allowedSides
        WHERE d.category.id IN :categoryIds
          AND d.enabled = true
          AND d.deletedAt IS NULL
          AND d.stockActual > 0
          AND (d.especial = false OR EXISTS (
              SELECT 1 FROM DishCalendarEntry dc
              WHERE dc.dishId = d.id AND dc.fecha = :fecha
          ))
    """)
    List<Dish> findAvailableForCategories(@Param("categoryIds") Set<Long> categoryIds,
                                          @Param("fecha") LocalDate fecha);

    /**
     * Decremento atómico de stock. Devuelve 1 si se pudo (había stock),
     * 0 si no (race condition o stock agotado).
     * El service layer debe validar el resultado.
     */
    @Modifying
    @Query("""
        UPDATE Dish d SET d.stockActual = d.stockActual - 1
        WHERE d.id = :dishId AND d.stockActual > 0
    """)
    int decrementStock(@Param("dishId") Long dishId);

    /** Devolución de stock al cancelar/editar pedido. */
    @Modifying
    @Query("UPDATE Dish d SET d.stockActual = d.stockActual + 1 WHERE d.id = :dishId")
    int incrementStock(@Param("dishId") Long dishId);

    /** Reset diario del stock (lo dispara el cron a las 00:00). */
    @Modifying
    @Query("UPDATE Dish d SET d.stockActual = d.stockDiarioDefault WHERE d.enabled = true AND d.deletedAt IS NULL")
    int resetAllStock();

    /** Conteo de platos activos (enabled + no soft-deleted) de una categoría. */
    @Query("""
        SELECT COUNT(d) FROM Dish d
        WHERE d.category.id = :categoryId
          AND d.enabled = true
          AND d.deletedAt IS NULL
    """)
    long countActiveByCategoryId(@Param("categoryId") Long categoryId);

    /** Deshabilita en bulk todos los platos activos de una categoría. Usado al archivar. */
    @Modifying
    @Query("""
        UPDATE Dish d SET d.enabled = false
        WHERE d.category.id = :categoryId
          AND d.enabled = true
          AND d.deletedAt IS NULL
    """)
    int disableAllByCategoryId(@Param("categoryId") Long categoryId);

    @Query("""
        SELECT DISTINCT d FROM Dish d
        LEFT JOIN FETCH d.allowedSides
        WHERE d.category.id IN :categoryIds
          AND d.enabled = true
          AND d.deletedAt IS NULL
          AND (d.especial = false OR EXISTS (
              SELECT 1 FROM DishCalendarEntry dc
              WHERE dc.dishId = d.id AND dc.fecha = :fecha
          ))
    """)
    List<Dish> findAvailableForCategoriesNoStock(@Param("categoryIds") Set<Long> categoryIds,
                                                  @Param("fecha") LocalDate fecha);

    @Modifying
    @Query(value = """
        UPDATE dish d SET stock_actual = GREATEST(0, d.stock_actual - (
            SELECT COUNT(*) FROM daily_choice dc
            WHERE dc.dish_id = d.id AND dc.fecha = :fecha AND dc.estado = 'PENDIENTE'
        ))
        WHERE d.enabled = true
        AND d.deleted_at IS NULL
        AND EXISTS (
            SELECT 1 FROM daily_choice dc
            WHERE dc.dish_id = d.id AND dc.fecha = :fecha AND dc.estado = 'PENDIENTE'
        )
    """, nativeQuery = true)
    int adjustStockForScheduledOrders(@Param("fecha") LocalDate fecha);
}
