package com.arias.orders.notifications;

import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderRepository;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Jobs y listener de notificaciones del ciclo del pedido (unidad 12, diseño
 * §Decisión 11). {@code OrderReminderScheduler} (recordatorio B2B "no
 * pediste todavía") NO se toca — este es un componente nuevo e
 * independiente.
 *
 * <p>Tres disparadores:
 * <ul>
 *   <li>{@link #sendDailySummaryIfDue()} — cron cada 5 min, chequea si cae en
 *       la ventana de {@code daily_summary_time}. Dedup vía {@code
 *       notification_run_log} (una vez por día), mismo patrón que {@code
 *       OrderReminderScheduler}.</li>
 *   <li>{@link #onOrderCancelled} — evento, {@code AFTER_COMMIT}: una
 *       cancelación que termina en rollback nunca dispara el mail.</li>
 *   <li>{@link #sendPickupReminders()} — cron cada minuto, dedup por pedido
 *       vía {@code Order.reminderSentAt}.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderNotificationScheduler {

    private static final String TIPO_DAILY_SUMMARY = "DAILY_SUMMARY";
    private static final int WINDOW_MINUTES = 5;
    private static final String ZONE = "America/Argentina/Buenos_Aires";

    private final OrderRepository orderRepo;
    private final UserRepository userRepo;
    private final RestaurantConfigRepository configRepo;
    private final NotificationRunLogRepository runLogRepo;
    private final OrderNotificationEmails emails;
    private final Clock clock;

    /**
     * Resumen matutino — chequea cada 5 minutos si cae en la ventana
     * {@code [daily_summary_time, daily_summary_time + 5min)}, igual patrón
     * que {@code OrderReminderScheduler.runIfDue}.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = ZONE)
    public void sendDailySummaryIfDue() {
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock);

        LocalTime target = configRepo.getSingleton().getDailySummaryTime();
        int nowMinutes = now.getHour() * 60 + now.getMinute();
        int targetMinutes = target.getHour() * 60 + target.getMinute();
        if (nowMinutes < targetMinutes || nowMinutes >= targetMinutes + WINDOW_MINUTES) {
            return;
        }

        if (!claimDailySummarySlot(today)) {
            return;
        }

        List<Order> orders = orderRepo.findByFechaAndEstadoNot(today, OrderEstado.CANCELADO);
        List<User> admins = userRepo.findActiveSuperAdmins();
        if (admins.isEmpty()) {
            log.info("[CRON-DAILY-SUMMARY] {} — sin administradores activos, no se envía", today);
            return;
        }

        emails.sendDailySummary(admins, today, orders);
        log.info("[CRON-DAILY-SUMMARY] {} — {} pedido(s), {} administrador(es)",
            today, orders.size(), admins.size());
    }

    /** Atomic claim — mismo patrón que {@code OrderReminderScheduler.claimRunSlot}. */
    @Transactional
    public boolean claimDailySummarySlot(LocalDate fecha) {
        NotificationRunLogId id = new NotificationRunLogId(TIPO_DAILY_SUMMARY, fecha);
        if (runLogRepo.existsById(id)) return false;
        try {
            runLogRepo.save(NotificationRunLog.builder()
                .tipo(TIPO_DAILY_SUMMARY)
                .fecha(fecha)
                .sentAt(Instant.now(clock))
                .recipients(0)
                .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /**
     * Alerta de cancelación — solo corre si la transacción que publicó el
     * evento efectivamente commiteó (diseño §Decisión 11).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCancelled(OrderCancelledEvent event) {
        List<User> admins = userRepo.findActiveSuperAdmins();
        emails.sendCancellationAlert(admins, event);
        log.info("[EVENT-CANCEL] Pedido #{} — alerta enviada a {} administrador(es) y al cliente",
            event.orderId(), admins.size());
    }

    /**
     * Recordatorio de retiro — chequea cada minuto los pedidos cuyo punto de
     * recordatorio ({@code pickup_at - pickup_reminder_minutes}) ya llegó,
     * excluye {@code CANCELADO} y dedupea por pedido vía claim atómico.
     */
    @Scheduled(cron = "0 * * * * *", zone = ZONE)
    public void sendPickupReminders() {
        Instant now = clock.instant();
        int reminderMinutes = configRepo.getSingleton().getPickupReminderMinutes();
        Instant cutoff = now.plus(reminderMinutes, ChronoUnit.MINUTES);

        List<Order> due = orderRepo.findByEstadoNotAndReminderSentAtIsNullAndPickupAtLessThanEqual(
            OrderEstado.CANCELADO, cutoff);

        int sent = 0;
        for (Order order : due) {
            int claimed = orderRepo.claimReminderSlot(order.getId(), now);
            if (claimed == 0) {
                // Otra instancia ya lo mandó, o se canceló entre el SELECT y el UPDATE.
                continue;
            }
            emails.sendPickupReminder(order);
            sent++;
        }

        if (sent > 0) {
            log.info("[CRON-PICKUP-REMINDER] {} recordatorio(s) enviado(s)", sent);
        }
    }
}
