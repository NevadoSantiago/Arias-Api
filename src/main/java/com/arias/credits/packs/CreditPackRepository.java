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

    /** ¿Hay un paquete vivo (no borrado) de este tipo? Para INDIVIDUAL/SUGERIDO, único. */
    boolean existsByPackTypeAndDeletedAtIsNull(CreditPackType packType);

    /**
     * Paquete INDIVIDUAL habilitado — usado por {@code CreditPurchaseService} para
     * derivar el precio por crédito de una compra directa (unidad 11, ver
     * nota de deviación en {@code tasks.md} 11.3): al no existir una tarifa
     * de créditos independiente del catálogo de paquetes, el paquete
     * INDIVIDUAL es la fuente de precio unitario.
     */
    Optional<CreditPack> findByPackTypeAndDeletedAtIsNullAndEnabledTrue(CreditPackType packType);
}
