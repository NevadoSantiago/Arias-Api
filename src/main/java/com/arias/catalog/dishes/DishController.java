package com.arias.catalog.dishes;

import com.arias.common.security.JwtUser;
import com.arias.orders.AffectedOrderDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dishes")
@RequiredArgsConstructor
public class DishController {

    private final DishService dishService;

    // ─── Empleado: lista visible ──────────────────────────────────────

    @GetMapping("/available")
    @PreAuthorize("isAuthenticated()")
    public List<DishDto> listAvailable(
        @AuthenticationPrincipal JwtUser user,
        @RequestParam(required = false) LocalDate fecha
    ) {
        return dishService.listAvailableFor(user.categoryId(), fecha);
    }

    // ─── Admin: CRUD completo ─────────────────────────────────────────

    @GetMapping("/admin")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<AdminDishDto> listAdmin() {
        return dishService.listAllForAdmin();
    }

    /** Lista los platos especiales activos. Usado por el calendario de especiales. */
    @GetMapping("/special")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<AdminDishDto> listSpecial() {
        return dishService.listSpecialForAdmin();
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AdminDishDto create(@Valid @RequestBody CreateDishRequest req) {
        return dishService.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AdminDishDto update(@PathVariable Long id, @Valid @RequestBody UpdateDishRequest req) {
        return dishService.update(id, req);
    }

    @GetMapping("/{id}/affected-orders")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<AffectedOrderDto> affectedOrders(@PathVariable Long id) {
        return dishService.findAffectedOrders(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> disable(
        @PathVariable Long id,
        @RequestParam(name = "cancelAffected", defaultValue = "false") boolean cancelAffected
    ) {
        dishService.disable(id, cancelAffected);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/enable")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> enable(@PathVariable Long id) {
        dishService.enable(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Soft-delete del plato. Requiere que esté deshabilitado primero.
     * Mismo patrón que el archive de empleados.
     */
    @DeleteMapping("/{id}/archive")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        dishService.archive(id);
        return ResponseEntity.noContent().build();
    }
}
