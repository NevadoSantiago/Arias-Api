package com.arias.catalog.dishes;

import com.arias.common.exception.BusinessException;
import com.arias.email.DisableNotificationEmails;
import com.arias.orders.DailyChoice;
import com.arias.orders.DailyChoiceRepository;
import com.arias.orders.OrderEstado;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Endpoints del calendario mensual de platos especiales.
 * Permite asignar/desasignar qué platos especiales aparecen cada día concreto.
 */
@RestController
@RequestMapping("/api/v1/admin/dish-calendar")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminDishCalendarController {

    private final DishCalendarRepository calendarRepo;
    private final DishRepository dishRepo;
    private final DailyChoiceRepository orderRepo;
    private final DisableNotificationEmails emails;
    private final Clock clock;

    /**
     * Devuelve las asignaciones de un rango de fechas, agrupadas por fecha.
     * Formato: {@code { "2026-05-01": [12, 18], "2026-05-02": [23] }}.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, List<Long>> getCalendar(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        if (from.isAfter(to)) {
            throw BusinessException.badRequest("invalid-range",
                "El parámetro 'from' debe ser menor o igual a 'to'");
        }
        List<DishCalendarEntry> entries = calendarRepo.findByFechaBetween(from, to);
        return entries.stream()
            .collect(Collectors.groupingBy(
                e -> e.getFecha().toString(),
                Collectors.mapping(DishCalendarEntry::getDishId, Collectors.toList())
            ));
    }

    /**
     * Reemplaza la lista completa de platos especiales asignados a una fecha.
     */
    @PutMapping
    @Transactional
    public void setForDate(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
        @RequestBody List<Long> dishIds
    ) {
        List<Long> normalized = dishIds == null ? List.of() : dishIds.stream().distinct().toList();

        for (Long id : normalized) {
            Dish d = dishRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("dish-not-found",
                    "Plato no encontrado: " + id));
            if (!Boolean.TRUE.equals(d.getEspecial())) {
                throw BusinessException.badRequest("not-special",
                    "Solo se pueden asignar platos especiales");
            }
            if (!Boolean.TRUE.equals(d.getEnabled())) {
                throw BusinessException.badRequest("dish-disabled",
                    "El plato '" + d.getNombre() + "' está deshabilitado");
            }
        }

        Set<Long> previous = calendarRepo.findByFecha(fecha).stream()
            .map(DishCalendarEntry::getDishId)
            .collect(Collectors.toSet());
        Set<Long> next = new HashSet<>(normalized);
        Set<Long> removed = new HashSet<>(previous);
        removed.removeAll(next);

        if (!removed.isEmpty()) {
            cancelPendingOrders(fecha, removed);
        }

        calendarRepo.deleteByFecha(fecha);
        calendarRepo.flush();
        for (Long id : normalized) {
            calendarRepo.save(new DishCalendarEntry(id, fecha));
        }
    }

    private void cancelPendingOrders(LocalDate fecha, Set<Long> removedDishIds) {
        boolean isToday = !fecha.isAfter(LocalDate.now(clock));
        for (Long dishId : removedDishIds) {
            List<DailyChoice> affected = orderRepo.findByFechaAndDishIdAndEstado(
                fecha, dishId, OrderEstado.PENDIENTE);
            for (DailyChoice order : affected) {
                if (isToday) {
                    dishRepo.incrementStock(order.getDish().getId());
                }
                emails.notifySpecialRemovedForDate(order);
                orderRepo.delete(order);
            }
        }
    }
}
