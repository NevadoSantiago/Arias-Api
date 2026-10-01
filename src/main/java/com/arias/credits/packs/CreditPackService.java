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

    /** Códigos históricos: solo se usan para derivar el tipo y generar el código. */
    private static final String LEGACY_INDIVIDUAL_CODE = "DAY";
    private static final String LEGACY_SUGERIDO_CODE = "WEEK";

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
        String requestedCode = req.code() == null || req.code().isBlank()
            ? null : req.code().trim().toUpperCase();
        CreditPackType type = resolveType(requestedCode, req.packType());

        if (type != CreditPackType.OTRO && repo.existsByPackTypeAndDeletedAtIsNull(type)) {
            throw BusinessException.conflict("credit-pack-type-duplicate",
                "Ya existe un paquete de tipo " + type);
        }
        String code = requestedCode != null ? requestedCode : generateCode(type, req.nombre());
        repo.findByCode(code).ifPresent(p -> {
            throw BusinessException.conflict("credit-pack-code-duplicate",
                "Ya existe un paquete con ese código");
        });

        CreditPack pack = CreditPack.builder()
            .code(code)
            .packType(type)
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

    /** Tipo explícito o, si falta, derivado del código; un código reservado no puede contradecir el tipo. */
    private CreditPackType resolveType(String code, CreditPackType requested) {
        CreditPackType derived = code == null ? null : switch (code) {
            case LEGACY_INDIVIDUAL_CODE -> CreditPackType.INDIVIDUAL;
            case LEGACY_SUGERIDO_CODE -> CreditPackType.SUGERIDO;
            default -> CreditPackType.OTRO;
        };
        if (requested == null) {
            return derived != null ? derived : CreditPackType.OTRO;
        }
        boolean reserved = derived != null && derived != CreditPackType.OTRO;
        if (reserved && derived != requested) {
            throw BusinessException.badRequest("credit-pack-type-code-mismatch",
                "El código " + code + " corresponde a un paquete " + derived
                    + " y contradice el tipo " + requested);
        }
        return requested;
    }

    /** INDIVIDUAL → DAY, SUGERIDO → WEEK (si están libres); el resto, un código único derivado del nombre. */
    private String generateCode(CreditPackType type, String nombre) {
        String preferred = switch (type) {
            case INDIVIDUAL -> LEGACY_INDIVIDUAL_CODE;
            case SUGERIDO -> LEGACY_SUGERIDO_CODE;
            case OTRO -> null;
        };
        if (preferred != null && repo.findByCode(preferred).isEmpty()) {
            return preferred;
        }
        String base = nombre.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.length() > 12) {
            base = base.substring(0, 12).replaceAll("-+$", "");
        }
        if (base.isEmpty()) {
            base = type.name();
        }
        for (int n = 1; ; n++) {
            String candidate = base + "-" + n;
            if (repo.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
    }

    private CreditPack findOrThrow(Long id) {
        return repo.findById(id)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> BusinessException.notFound("credit-pack-not-found",
                "Paquete de créditos no encontrado"));
    }
}
