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
import java.util.List;

@RestController
@RequestMapping("/api/v1/restaurant-config")
@RequiredArgsConstructor
public class RestaurantConfigController {

    private final RestaurantConfigRepository repo;
    private final FechaDeshabilitadaRepository fechaDeshabilitadaRepo;

    /** Lectura del singleton — accesible para cualquier usuario autenticado. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public RestaurantConfigDto get() {
        return RestaurantConfigDto.from(repo.getSingleton());
    }

    /** Edición del singleton — solo el SUPER_ADMIN del resto. */
    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public RestaurantConfigDto update(@Valid @RequestBody UpdateRestaurantConfigRequest req) {
        RestaurantConfig config = repo.getSingleton();
        config.setHoraCorte(req.horaCorte());
        repo.save(config);
        return RestaurantConfigDto.from(config);
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
