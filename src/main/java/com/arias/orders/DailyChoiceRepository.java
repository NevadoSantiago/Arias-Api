package com.arias.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyChoiceRepository extends JpaRepository<DailyChoice, Long> {

    /** El pedido del usuario para una fecha (único por la UNIQUE constraint). */
    Optional<DailyChoice> findByUserIdAndFecha(Long userId, LocalDate fecha);

    /** Pedidos del día para una empresa — usado por el panel del CompanyAdmin. */
    List<DailyChoice> findAllByCompanyIdAndFechaOrderByHoraEntregaAsc(Long companyId, LocalDate fecha);

    /** Todos los pedidos del día — usado por el dashboard del SUPER_ADMIN. */
    List<DailyChoice> findAllByFechaOrderByCompanyIdAscHoraEntregaAsc(LocalDate fecha);

    /**
     * Agregación de facturación: pedidos servidos en un rango, agrupados por
     * empresa × categoría × precio congelado.
     *
     * <p>El criterio facturable es {@code confirmedAt IS NOT NULL}, NO
     * {@code estado = CONFIRMADO}. El campo {@code estado} se SOBREESCRIBE al
     * avanzar (CONFIRMADO → COMANDADO → ENTREGADO), así que filtrar por él
     * devolvería solo los pedidos que nunca se cocinaron. {@code confirmedAt}
     * lo setea el cron de corte y no se pisa nunca más.
     *
     * <p>Los pedidos cancelados no requieren filtro: {@code OrderService.cancel}
     * borra la fila, no existe estado CANCELADO.
     *
     * <p>{@code LEFT JOIN} en category a propósito — un inner join descartaría
     * en silencio los pedidos anteriores a V14 sin categoría, que es justo lo
     * que el reporte necesita mostrar como alerta.
     */
    /**
     * @param companyId null = todas las empresas. El {@code :companyId IS NULL}
     *                  evita duplicar la query para el caso filtrado.
     */
    @Query("""
        SELECT new com.arias.billing.BillingRow(
            c.id, c.nombre, cat.id, cat.nombre, d.dishCategoria, d.precioSnapshot, COUNT(d))
        FROM DailyChoice d
        JOIN d.company c
        LEFT JOIN d.category cat
        WHERE d.confirmedAt IS NOT NULL
          AND d.fecha BETWEEN :desde AND :hasta
          AND (:companyId IS NULL OR c.id = :companyId)
        GROUP BY c.id, c.nombre, cat.id, cat.nombre, d.dishCategoria, d.precioSnapshot
        ORDER BY c.nombre ASC, d.dishCategoria ASC
    """)
    List<com.arias.billing.BillingRow> aggregateBilling(@Param("desde") LocalDate desde,
                                                        @Param("hasta") LocalDate hasta,
                                                        @Param("companyId") Long companyId);

    /**
     * Totales por día del período — evolución de la facturación.
     *
     * <p>Mismo criterio y mismo filtro que {@link #aggregateBilling}: los dos
     * cortes tienen que sumar lo mismo o el reporte se contradice a sí mismo.
     *
     * <p>{@code COALESCE} adentro del SUM: sin él, un día donde TODOS los pedidos
     * son anteriores a V14 (precio null) devolvería total null en vez de 0.
     *
     * <p>Solo devuelve días CON pedidos. Los días sin actividad no existen como
     * fila — el frontend los muestra como hueco, que es la verdad.
     */
    @Query("""
        SELECT new com.arias.billing.DailyTotalRow(
            d.fecha, COUNT(d), SUM(COALESCE(d.precioSnapshot, 0)))
        FROM DailyChoice d
        WHERE d.confirmedAt IS NOT NULL
          AND d.fecha BETWEEN :desde AND :hasta
          AND (:companyId IS NULL OR d.company.id = :companyId)
        GROUP BY d.fecha
        ORDER BY d.fecha ASC
    """)
    List<com.arias.billing.DailyTotalRow> aggregateDailyTotals(@Param("desde") LocalDate desde,
                                                               @Param("hasta") LocalDate hasta,
                                                               @Param("companyId") Long companyId);

    /**
     * Cron de corte: pasa todos los pedidos PENDIENTE → CONFIRMADO para una fecha.
     * Setea confirmed_at = NOW().
     */
    @Modifying
    @Query("""
        UPDATE DailyChoice d
        SET d.estado = com.arias.orders.OrderEstado.CONFIRMADO,
            d.confirmedAt = :now
        WHERE d.fecha = :fecha
          AND d.estado = com.arias.orders.OrderEstado.PENDIENTE
    """)
    int closeAllPendingForDate(@Param("fecha") LocalDate fecha, @Param("now") Instant now);

    /**
     * Cron de corte: cierra TODOS los pedidos PENDIENTE de fechas anteriores a
     * la fecha dada. Robustez: si el server estuvo caído al horario de corte,
     * el próximo tick recupera los pedidos huérfanos.
     */
    @Modifying
    @Query("""
        UPDATE DailyChoice d
        SET d.estado = com.arias.orders.OrderEstado.CONFIRMADO,
            d.confirmedAt = :now
        WHERE d.fecha < :today
          AND d.estado = com.arias.orders.OrderEstado.PENDIENTE
    """)
    int closeAllPendingBeforeDate(@Param("today") LocalDate today, @Param("now") Instant now);

    @Modifying
    @Query("""
        UPDATE DailyChoice d
        SET d.estado = com.arias.orders.OrderEstado.COMANDADO,
            d.comandadoAt = :now
        WHERE d.company.id = :companyId
          AND d.fecha = :fecha
          AND d.estado = com.arias.orders.OrderEstado.CONFIRMADO
    """)
    int markComandadoByCompany(@Param("companyId") Long companyId,
                               @Param("fecha") LocalDate fecha,
                               @Param("now") Instant now);

    @Modifying
    @Query("""
        UPDATE DailyChoice d
        SET d.estado = com.arias.orders.OrderEstado.ENTREGADO,
            d.deliveredAt = :now
        WHERE d.company.id = :companyId
          AND d.fecha = :fecha
          AND d.estado IN (com.arias.orders.OrderEstado.CONFIRMADO, com.arias.orders.OrderEstado.COMANDADO)
    """)
    int markDeliveredByCompany(@Param("companyId") Long companyId,
                               @Param("fecha") LocalDate fecha,
                               @Param("now") Instant now);

    @Query("SELECT d.fecha, COUNT(d) FROM DailyChoice d WHERE d.company.id = :companyId AND d.fecha >= :since GROUP BY d.fecha ORDER BY d.fecha")
    List<Object[]> countDailyByCompany(@Param("companyId") Long companyId, @Param("since") LocalDate since);

    @Query("SELECT d.dishCategoria, COUNT(d) FROM DailyChoice d WHERE d.company.id = :companyId AND d.fecha >= :since GROUP BY d.dishCategoria")
    List<Object[]> countByCategoryForCompany(@Param("companyId") Long companyId, @Param("since") LocalDate since);

    @Query("SELECT COUNT(d) FROM DailyChoice d WHERE d.company.id = :companyId AND d.fecha = :fecha")
    long countByCompanyAndFecha(@Param("companyId") Long companyId, @Param("fecha") LocalDate fecha);

    List<DailyChoice> findByUserIdAndFechaBetweenOrderByFechaAsc(Long userId, LocalDate from, LocalDate to);

    /** Último pedido del usuario para un plato específico — usado para sugerir preferencias. */
    Optional<DailyChoice> findFirstByUserIdAndDishIdOrderByFechaDesc(Long userId, Long dishId);

    /**
     * Último pedido del usuario en el MISMO día de la semana (anterior a hoy).
     * Usa Postgres EXTRACT(DOW): Sun=0, Mon=1, ..., Sat=6.
     */
    @Query(value = """
        SELECT * FROM daily_choice
        WHERE user_id = :userId
          AND EXTRACT(DOW FROM fecha) = :dow
          AND fecha < CURRENT_DATE
        ORDER BY fecha DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<DailyChoice> findLastSameWeekdayBeforeToday(
        @Param("userId") Long userId,
        @Param("dow") int dow);

    /** Pedidos para una fecha + plato + estado — usado cuando se remueve un especial del calendario. */
    @Query("""
        SELECT d FROM DailyChoice d
        WHERE d.fecha = :fecha
          AND d.dish.id = :dishId
          AND d.estado = :estado
    """)
    List<DailyChoice> findByFechaAndDishIdAndEstado(
        @Param("fecha") LocalDate fecha,
        @Param("dishId") Long dishId,
        @Param("estado") OrderEstado estado);

    /** Pedidos PENDIENTE que tienen este plato — usado al desactivar un dish. */
    @Query("""
        SELECT d FROM DailyChoice d
        WHERE d.dish.id = :dishId
          AND d.estado = com.arias.orders.OrderEstado.PENDIENTE
    """)
    List<DailyChoice> findPendingByDish(@Param("dishId") Long dishId);

    /** Pedidos PENDIENTE que tienen este side — usado al desactivar un side. */
    @Query("""
        SELECT d FROM DailyChoice d
        WHERE d.side.id = :sideId
          AND d.estado = com.arias.orders.OrderEstado.PENDIENTE
    """)
    List<DailyChoice> findPendingBySide(@Param("sideId") Long sideId);
}
