package com.arias.credits.packs;

import com.arias.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * CRUD de paquetes de créditos — {@code SUPER_ADMIN} bajo
 * {@code /api/v1/admin/credit-packs} (unidad 11, tarea 11.2, diseño
 * §Decisión 12). Mismo patrón que {@code MenuSectionService}: código de
 * negocio en el service, el controller solo enruta.
 */
@Service
@RequiredArgsConstructor
public class CreditPackService {

    private final CreditPackRepository repo;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<CreditPackDto> listPublic() {
        return repo.findAllByDeletedAtIsNullAndEnabledTrueOrderByOrdenDisplayAsc().stream()
            .map(CreditPackDto::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<CreditPackDto> listAdmin() {
        return repo.findAllByDeletedAtIsNullOrderByOrdenDisplayAsc().stream()
            .map(CreditPackDto::from)
            .toList();
    }

    @Transactional
    public CreditPackDto create(CreateCreditPackRequest req) {
        repo.findByCode(req.code()).ifPresent(p -> {
            throw BusinessException.conflict("credit-pack-code-duplicate",
                "Ya existe un paquete con ese código");
        });

        CreditPack pack = CreditPack.builder()
            .code(req.code().trim().toUpperCase())
            .nombre(req.nombre().trim())
            .creditAmount(req.creditAmount())
            .priceCents(req.priceCents())
            .discountPercent(req.discountPercent())
            .ordenDisplay(req.ordenDisplay())
            .enabled(true)
            .build();
        return CreditPackDto.from(repo.save(pack));
    }

    @Transactional
    public CreditPackDto update(Long id, UpdateCreditPackRequest req) {
        CreditPack pack = findOrThrow(id);
        pack.setNombre(req.nombre().trim());
        pack.setCreditAmount(req.creditAmount());
        pack.setPriceCents(req.priceCents());
        pack.setDiscountPercent(req.discountPercent());
        pack.setOrdenDisplay(req.ordenDisplay());
        pack.setEnabled(req.enabled());
        return CreditPackDto.from(pack);
    }

    /** Soft delete — un paquete borrado deja de ofrecerse; las compras ya hechas no se tocan (FK lógica). */
    @Transactional
    public void delete(Long id) {
        CreditPack pack = findOrThrow(id);
        pack.setDeletedAt(clock.instant());
        pack.setEnabled(false);
    }

    private CreditPack findOrThrow(Long id) {
        return repo.findById(id)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> BusinessException.notFound("credit-pack-not-found",
                "Paquete de créditos no encontrado"));
    }
}
