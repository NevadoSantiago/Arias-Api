package com.arias.orders;

import com.arias.common.exception.BusinessException;
import com.arias.restaurantconfig.FechaDeshabilitadaRepository;
import com.arias.restaurantconfig.RestaurantConfig;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Programación de retiro — spec {@code pickup-scheduling} (unidad 8). Deriva
 * los horarios de retiro ofrecidos a partir de {@code restaurant_config}
 * (migración V20): slots cada {@code pickup_slot_minutes} entre {@code
 * pickup_window_start} y {@code pickup_window_end}, filtrando los previos a
 * {@code now + pickup_lead_minutes}, solo semana actual y la siguiente,
 * excluyendo fechas deshabilitadas. Sin límite de capacidad por horario: esta
 * clase nunca consulta {@code orders}, así que el resultado es el mismo sin
 * importar cuántos pedidos ya existan para ese horario.
 *
 * <p>{@code pickup_lead_minutes} es un solo valor con dos usos (diseño
 * §"pickup_lead_minutes: un solo valor, dos usos"): acá define el retiro más
 * temprano ofrecido; {@link OrderConsumptionScheduler} usa el mismo valor
 * para el punto de consumo automático.
 */
@Service
@RequiredArgsConstructor
public class PickupSlotService {

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final RestaurantConfigRepository configRepo;
    private final FechaDeshabilitadaRepository fechaDeshabilitadaRepo;
    private final Clock clock;

    /**
     * Horarios de retiro válidos para {@code fecha}. Lista vacía si la fecha
     * está fuera de la ventana de semana actual/siguiente o si está
     * deshabilitada — nunca lanza, porque es una consulta de disponibilidad,
     * no una validación de un horario puntual (ver {@link
     * #assertValidPickupTime}).
     */
    public List<Instant> slotsFor(LocalDate fecha) {
        LocalDate today = LocalDate.now(clock);
        if (!isWithinSchedulableWeeks(fecha, today) || fechaDeshabilitadaRepo.existsByFecha(fecha)) {
            return List.of();
        }

        RestaurantConfig config = configRepo.getSingleton();
        Instant earliest = clock.instant().plus(config.getPickupLeadMinutes(), ChronoUnit.MINUTES);

        LocalTime start = config.getPickupWindowStart();
        LocalTime end = config.getPickupWindowEnd();
        int stepMinutes = config.getPickupSlotMinutes();

        List<Instant> slots = new ArrayList<>();
        for (LocalTime t = start; t.isBefore(end); t = t.plusMinutes(stepMinutes)) {
            Instant candidate = fecha.atTime(t).atZone(ZONE).toInstant();
            if (!candidate.isBefore(earliest)) {
                slots.add(candidate);
            }
        }
        return slots;
    }

    /**
     * Valida un horario de retiro puntual elegido por el cliente — usado por
     * {@code OrderPlacementService.place()} para rechazar con un error de
     * negocio claro en vez de aceptar cualquier instante. A diferencia de
     * {@link #slotsFor}, NO chequea fechas deshabilitadas: esa exclusión solo
     * afecta qué horarios se OFRECEN, no es un requisito de la spec {@code
     * pickup-scheduling} para el pedido puntual.
     */
    public void assertValidPickupTime(Instant pickupAt) {
        LocalDate today = LocalDate.now(clock);
        LocalDate fecha = LocalDate.ofInstant(pickupAt, ZONE);

        if (!isWithinSchedulableWeeks(fecha, today)) {
            throw BusinessException.badRequest("pickup-out-of-range",
                "La fecha de retiro debe estar dentro de la semana actual o la siguiente");
        }

        // Los días deshabilitados no se listan en slotsFor, pero la validación
        // no puede depender de eso: un pedido llega por API, no por la UI.
        if (fechaDeshabilitadaRepo.existsByFecha(fecha)) {
            throw BusinessException.badRequest("pickup-date-disabled",
                "El restaurante no recibe pedidos para esa fecha");
        }

        RestaurantConfig config = configRepo.getSingleton();
        LocalTime timeOfDay = pickupAt.atZone(ZONE).toLocalTime();
        if (timeOfDay.isBefore(config.getPickupWindowStart()) || !timeOfDay.isBefore(config.getPickupWindowEnd())) {
            throw BusinessException.badRequest("pickup-outside-service-window",
                "El horario de retiro debe estar entre " + config.getPickupWindowStart()
                    + " y " + config.getPickupWindowEnd());
        }

        Instant earliest = clock.instant().plus(config.getPickupLeadMinutes(), ChronoUnit.MINUTES);
        if (pickupAt.isBefore(earliest)) {
            throw BusinessException.badRequest("pickup-too-soon",
                "El horario de retiro debe ser al menos " + config.getPickupLeadMinutes()
                    + " minutos desde ahora");
        }
    }

    /** Semana calendario actual (lunes a domingo) más la siguiente — ambas inclusive. */
    private boolean isWithinSchedulableWeeks(LocalDate fecha, LocalDate today) {
        LocalDate mondayThisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sundayNextWeek = mondayThisWeek.plusDays(13);
        return !fecha.isBefore(mondayThisWeek) && !fecha.isAfter(sundayNextWeek);
    }
}
