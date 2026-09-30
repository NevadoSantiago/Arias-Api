package com.arias.credits.packs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditPackRepository extends JpaRepository<CreditPack, Long> {

    Optional<CreditPack> findByCode(String code);

    /** Catálogo público — visible para compra (`GET /api/v1/credits/packs`). */
    List<CreditPack> findAllByDeletedAtIsNullAndEnabledTrueOrderByOrdenDisplayAsc();

    /** Listado admin — incluye deshabilitados, excluye borrados. */
    List<CreditPack> findAllByDeletedAtIsNullOrderByOrdenDisplayAsc();

    /**
     * Paquete DAY habilitado — usado por {@code CreditPurchaseService} para
     * derivar el precio por crédito de una compra directa (unidad 11, ver
     * nota de deviación en {@code tasks.md} 11.3): al no existir una tarifa
     * de créditos independiente del catálogo de paquetes, el paquete DAY —
     * la denominación más chica — es la única fuente de precio unitario
     * disponible en el diseño.
     */
    Optional<CreditPack> findByCodeAndDeletedAtIsNullAndEnabledTrue(String code);
}
