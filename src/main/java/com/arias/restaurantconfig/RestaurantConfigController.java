package com.arias.restaurantconfig;

import com.arias.common.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/restaurant-config")
@RequiredArgsConstructor
public class RestaurantConfigController {

    private final RestaurantConfigRepository repo;
    private final FechaDeshabilitadaRepository fechaDeshabilitadaRepo;
    private final PickupScheduleRepository pickupScheduleRepo;

    /** Lectura del singleton — accesible para cualquier usuario autenticado. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public RestaurantConfigDto get() {
        return RestaurantConfigDto.from(repo.getSingleton(), pickupScheduleDtos());
    }

    /**
     * Edición del singleton — solo el SUPER_ADMIN del resto. Incluye los
     * siete campos de configuración B2C de la unidad 8 (migración V20):
     * tiempo de preparación único, vencimiento de créditos y ventana de
     * pedidos.
     */
    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public RestaurantConfigDto update(@Valid @RequestBody UpdateRestaurantConfigRequest req) {
        if (!req.pickupWindowStart().isBefore(req.pickupWindowEnd())) {
            throw BusinessException.badRequest("invalid-pickup-window",
                "La ventana de pedidos debe abrir antes de cerrar");
        }

        RestaurantConfig config = repo.getSingleton();
        config.setHoraCorte(req.horaCorte());
        config.setPickupLeadMinutes(req.pickupLeadMinutes());
        config.setCreditExpiryDays(req.creditExpiryDays());
        config.setPickupWindowStart(req.pickupWindowStart());
        config.setPickupWindowEnd(req.pickupWindowEnd());
        config.setPickupSlotMinutes(req.pickupSlotMinutes());
        config.setDailySummaryTime(req.dailySummaryTime());
        config.setPickupReminderMinutes(req.pickupReminderMinutes());
        repo.save(config);
        return RestaurantConfigDto.from(config, pickupScheduleDtos());
    }

    // ─── Franja de retiro por día de la semana (migración V24, B5/F14) ────

    /**
     * Reemplaza la franja de los 7 días de la semana — solo el
     * {@code SUPER_ADMIN}. Valida a mano (no expresable con anotaciones
     * simples): exactamente los 7 días ISO (1..7), sin duplicados, y para
     * cada día abierto ambos horarios presentes con cierre después de
     * apertura. La alineación al paso configurado NO se exige acá: es
     * relativa al inicio de la ventana de cada día (ver {@link
     * com.arias.orders.PickupSlotService}), así que cualquier apertura es
     * válida.
     */
    @PutMapping("/pickup-schedule")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public List<PickupScheduleDayDto> updatePickupSchedule(@RequestBody List<PickupScheduleDayRequest> req) {
        validatePickupSchedule(req);

        for (PickupScheduleDayRequest day : req) {
            PickupSchedule schedule = pickupScheduleRepo.getByDayOfWeek(day.dayOfWeek());
            schedule.setOpen(day.open());
            schedule.setWindowStart(day.open() ? day.windowStart() : null);
            schedule.setWindowEnd(day.open() ? day.windowEnd() : null);
            pickupScheduleRepo.save(schedule);
        }

        return pickupScheduleDtos();
    }

    private void validatePickupSchedule(List<PickupScheduleDayRequest> req) {
        if (req == null || req.size() != 7) {
            throw BusinessException.badRequest("invalid-pickup-schedule",
                "Se deben enviar exactamente los 7 días de la semana");
        }

        Set<Integer> seen = new HashSet<>();
        for (PickupScheduleDayRequest day : req) {
            if (day == null) {
                throw BusinessException.badRequest("invalid-pickup-schedule",
                    "Cada día de la semana debe venir completo");
            }
            if (day.dayOfWeek() == null || day.dayOfWeek() < 1 || day.dayOfWeek() > 7) {
                throw BusinessException.badRequest("invalid-pickup-schedule",
                    "dayOfWeek debe estar entre 1 (lunes) y 7 (domingo)");
            }
            if (!seen.add(day.dayOfWeek())) {
                throw BusinessException.badRequest("invalid-pickup-schedule",
                    "Día de la semana duplicado: " + day.dayOfWeek());
            }
            if (day.open()) {
                if (day.windowStart() == null || day.windowEnd() == null) {
                    throw BusinessException.badRequest("invalid-pickup-schedule",
                        "Un día abierto necesita horario de apertura y cierre");
                }
                if (!day.windowStart().isBefore(day.windowEnd())) {
                    throw BusinessException.badRequest("invalid-pickup-schedule",
                        "El horario de apertura debe ser antes del cierre");
                }
            }
        }
    }

    private List<PickupScheduleDayDto> pickupScheduleDtos() {
        return pickupScheduleRepo.findAllByOrderByDayOfWeekAsc().stream()
            .map(PickupScheduleDayDto::from)
            .toList();
    }

    // ─── Fechas deshabilitadas ────────────────────────────────────────────

    @GetMapping("/disabled-dates")
    @PreAuthorize("isAuthenticated()")
    public List<DisabledDateDto> getDisabledDates(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<FechaDeshabilitada> fechas;
        if (from != null && to != null) {
            fechas = fechaDeshabilitadaRepo.findByFechaBetweenOrderByFechaAsc(from, to);
        } else {
            fechas = fechaDeshabilitadaRepo.findByFechaGreaterThanEqualOrderByFechaAsc(LocalDate.now());
        }
        return fechas.stream().map(DisabledDateDto::from).toList();
    }

    @PostMapping("/disabled-dates")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public DisabledDateDto createDisabledDate(@Valid @RequestBody CreateDisabledDateRequest req) {
        if (fechaDeshabilitadaRepo.existsByFecha(req.fecha())) {
            throw BusinessException.conflict("date-already-disabled", "Esa fecha ya está deshabilitada");
        }
        FechaDeshabilitada entity = FechaDeshabilitada.builder()
                .fecha(req.fecha())
                .motivo(req.motivo())
                .build();
        return DisabledDateDto.from(fechaDeshabilitadaRepo.save(entity));
    }

    @DeleteMapping("/disabled-dates/{fecha}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDisabledDate(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        if (!fechaDeshabilitadaRepo.existsByFecha(fecha)) {
            throw BusinessException.notFound("date-not-found", "No hay fecha deshabilitada para esa fecha");
        }
        fechaDeshabilitadaRepo.deleteByFecha(fecha);
    }
}
