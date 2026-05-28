package com.arias.orders;

import com.arias.catalog.dishes.DishRepository;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Jobs programados del dominio de orders.
 *
 * <p>Todos los tiempos se interpretan en {@code America/Argentina/Buenos_Aires}
 * — coherente con el {@link Clock} inyectado.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrdersScheduler {

    private final DishRepository dishRepo;
    private final DailyChoiceRepository orderRepo;
    private final RestaurantConfigRepository configRepo;
    private final Clock clock;

    /**
     * Reset diario de stock — todos los días a las 00:00.
     * Cada {@code Dish.stockActual} vuelve a su {@code stockDiarioDefault}.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "America/Argentina/Buenos_Aires")
    @Transactional
    public void resetDailyStock() {
        int reset = dishRepo.resetAllStock();
        log.info("[CRON] Reset diario de stock — {} platos afectados", reset);

        LocalDate today = LocalDate.now(clock);
        int adjusted = dishRepo.adjustStockForScheduledOrders(today);
        if (adjusted > 0) {
            log.info("[CRON] Stock ajustado por {} platos con pedidos programados", adjusted);
        }
    }

    /**
     * Cierre de pedidos vencidos — chequea cada minuto.
     * Cierra todos los pedidos PENDIENTE cuya {@code hora_corte} ya pasó
     * (sea de hoy o de fechas anteriores).
     *
     * <p>El check periódico (en vez de un solo dispatch a la hora_corte) hace
     * que el sistema sea robusto a: cambios de hora_corte en runtime, downtime
     * del server, ediciones manuales de la BD.
     */
    @Scheduled(cron = "30 * * * * *", zone = "America/Argentina/Buenos_Aires")
    @Transactional
    public void closeOverdueOrders() {
        LocalDate today = LocalDate.now(clock);
        LocalTime nowTime = LocalTime.now(clock);
        Instant now = Instant.now(clock);

        LocalTime cutoff = configRepo.getSingleton().getHoraCorte();
        boolean pastTodayCutoff = !nowTime.isBefore(cutoff);

        // Cierre de fechas anteriores (siempre, son pedidos huérfanos)
        int closedPast = orderRepo.closeAllPendingBeforeDate(today, now);

        // Cierre del día actual, solo si ya pasó el horario de corte
        int closedToday = pastTodayCutoff
            ? orderRepo.closeAllPendingForDate(today, now)
            : 0;

        int total = closedPast + closedToday;
        if (total > 0) {
            log.info("[CRON] Cierre de pedidos: {} cerrados (pasadas: {}, hoy: {})",
                total, closedPast, closedToday);
        }
    }
}
