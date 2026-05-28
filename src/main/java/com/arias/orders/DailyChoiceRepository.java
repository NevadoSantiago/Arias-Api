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
        WHERE d.id = :id
          AND d.estado = com.arias.orders.OrderEstado.CONFIRMADO
    """)
    int markComandado(@Param("id") Long id, @Param("now") Instant now);

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
        WHERE d.id = :id
          AND d.estado IN (com.arias.orders.OrderEstado.CONFIRMADO, com.arias.orders.OrderEstado.COMANDADO)
    """)
    int markDelivered(@Param("id") Long id, @Param("now") Instant now);

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
