package com.arias.catalog.desserts;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Administración de postres. La foto se sube con el presign existente ({@code POST /api/v1/uploads/dish-photo}). */
@RestController
@RequestMapping("/api/v1/admin/desserts")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminDessertController {

    private final DessertService service;

    @GetMapping
    public List<AdminDessertDto> list() {
        return service.listAllForAdmin();
    }

    @PostMapping
    public AdminDessertDto create(@Valid @RequestBody CreateDessertRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public AdminDessertDto update(@PathVariable Long id, @Valid @RequestBody UpdateDessertRequest req) {
        return service.update(id, req);
    }

    @PutMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable Long id) {
        service.enable(id);
        return ResponseEntity.noContent().build();
    }

    /** Deshabilita el postre (no lo borra). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disable(@PathVariable Long id) {
        service.disable(id);
        return ResponseEntity.noContent().build();
    }
}
