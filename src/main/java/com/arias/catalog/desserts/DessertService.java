package com.arias.catalog.desserts;

import com.arias.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** CRUD admin y listado para clientes del catálogo de postres. */
@Service
@RequiredArgsConstructor
public class DessertService {

    private final DessertRepository repo;

    @Transactional(readOnly = true)
    public List<AdminDessertDto> listAllForAdmin() {
        return repo.findByDeletedAtIsNullOrderByNombreAsc().stream().map(AdminDessertDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DessertDto> listAvailable() {
        return repo.findByEnabledTrueAndDeletedAtIsNullOrderByNombreAsc().stream().map(DessertDto::from).toList();
    }

    /** El stock de hoy arranca igual al stock diario por defecto. */
    @Transactional
    public AdminDessertDto create(CreateDessertRequest req) {
        Dessert dessert = Dessert.builder()
            .nombre(req.nombre().trim())
            .descripcion(trimOrNull(req.descripcion()))
            .fotoUrl(trimOrNull(req.fotoUrl()))
            .stockDiarioDefault(req.stockDiarioDefault())
            .stockActual(req.stockDiarioDefault())
            .enabled(true)
            .build();
        return AdminDessertDto.from(repo.save(dessert));
    }

    /** Targeted UPDATEs: an edit never rewrites {@code stock_actual} unless the request sends it. */
    @Transactional
    public AdminDessertDto update(Long id, UpdateDessertRequest req) {
        find(id);
        repo.updateDetails(id, req.nombre().trim(), trimOrNull(req.descripcion()), trimOrNull(req.fotoUrl()),
            req.stockDiarioDefault(), req.enabled());
        if (req.stockActual() != null) {
            repo.updateStock(id, req.stockActual());
        }
        return AdminDessertDto.from(find(id));
    }

    @Transactional
    public void enable(Long id) {
        find(id);
        repo.updateEnabled(id, true);
    }

    @Transactional
    public void disable(Long id) {
        find(id);
        repo.updateEnabled(id, false);
    }

    private Dessert find(Long id) {
        return repo.findById(id)
            .filter(d -> d.getDeletedAt() == null)
            .orElseThrow(() -> BusinessException.notFound("dessert-not-found", "Postre no encontrado"));
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
