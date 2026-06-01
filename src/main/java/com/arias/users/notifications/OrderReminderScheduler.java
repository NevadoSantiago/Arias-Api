package com.arias.users.notifications;

import com.arias.restaurantconfig.FechaDeshabilitadaRepository;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Cron del recordatorio diario "¿te olvidaste tu almuerzo?".
 *
 * <p>Corre cada 5 minutos de lunes a viernes. En cada tick:
 * <ol>
 *   <li>Lee {@code horaCorte} del restaurant.</li>
 *   <li>Computa el {@code targetTime = horaCorte - 1h}.</li>
 *   <li>Si la hora actual cae en {@code [targetTime, targetTime + 5min)} → es el momento de mandar.</li>
 *   <li>Insert atómico en {@code reminder_run_log} con la fecha como PK. Si ya existe → otra instancia ganó la carrera, salimos sin re-enviar.</li>
 *   <li>Si el INSERT prosperó: busca usuarios elegibles y manda mails.</li>
 * </ol>
 *
 * <p>El dedup vía PK es la pieza clave — sobrevive a reinicios y solapamientos.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderReminderScheduler {

    private static final int WINDOW_MINUTES = 5;

    private final RestaurantConfigRepository configRepo;
    private final FechaDeshabilitadaRepository fechaDeshabilitadaRepo;
    private final UserRepository userRepo;
    private final ReminderRunLogRepository runLogRepo;
    private final OrderReminderEmail email;
    private final Clock clock;

    @Scheduled(cron = "0 */5 * * * MON-FRI", zone = "America/Argentina/Buenos_Aires")
    public void runIfDue() {
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock);

        LocalTime horaCorte = configRepo.getSingleton().getHoraCorte();
        LocalTime target = horaCorte.minusHours(1);

        // Ventana [target, target+5min). Comparamos minutos desde medianoche
        // para evitar overflow si target es 00:0X.
        int nowMinutes = now.getHour() * 60 + now.getMinute();
        int targetMinutes = target.getHour() * 60 + target.getMinute();
        if (nowMinutes < targetMinutes || nowMinutes >= targetMinutes + WINDOW_MINUTES) {
            return;
        }

        if (fechaDeshabilitadaRepo.existsByFecha(today)) {
            log.info("[CRON-REMINDER] {} está deshabilitada, no se envía recordatorio", today);
            return;
        }

        if (!claimRunSlot(today)) {
            // Otra instancia ya envió (o estamos en el mismo cron pero ya pasamos).
            return;
        }

        List<User> recipients = userRepo.findReminderRecipientsForDate(today);
        if (recipients.isEmpty()) {
            log.info("[CRON-REMINDER] {} — 0 destinatarios", today);
            updateRecipientCount(today, 0);
            return;
        }

        for (User u : recipients) {
            email.send(u);
        }
        log.info("[CRON-REMINDER] {} — enviado a {} empleados", today, recipients.size());
        updateRecipientCount(today, recipients.size());
    }

    /**
     * Atomic claim: intenta insertar la fila del día. Si ya existe, la PK
     * collision la rechaza y devolvemos false.
     */
    @Transactional
    public boolean claimRunSlot(LocalDate fecha) {
        if (runLogRepo.existsById(fecha)) return false;
        try {
            runLogRepo.save(ReminderRunLog.builder()
                .fecha(fecha)
                .sentAt(Instant.now(clock))
                .recipients(0)
                .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    @Transactional
    public void updateRecipientCount(LocalDate fecha, int count) {
        runLogRepo.findById(fecha).ifPresent(log -> log.setRecipients(count));
    }
}
