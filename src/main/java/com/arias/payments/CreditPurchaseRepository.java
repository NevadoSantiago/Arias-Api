package com.arias.payments;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditPurchaseRepository extends JpaRepository<CreditPurchase, UUID> {

    Optional<CreditPurchase> findByIdAndUserId(UUID id, Long userId);

    /**
     * {@code SELECT ... FOR UPDATE} sobre la compra — mismo patrón que
     * {@code CreditWalletRepository#findByIdForUpdate}: el webhook y la
     * reconciliación bloquean esta fila antes de decidir si ya está
     * acreditada (diseño §Flujo de datos, paso 6 "atajo de ya acreditada").
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM CreditPurchase p WHERE p.id = :id")
    Optional<CreditPurchase> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Candidatas a reconciliación (unidad 11, {@code
     * PaymentReconciliationScheduler}): {@code PENDING} creadas antes del
     * corte dado — el scheduler aplica los dos cortes (30 min re-consulta, 24
     * h expira) sobre el mismo resultado, ordenando por antigüedad.
     */
    List<CreditPurchase> findByStatusAndCreatedAtBefore(CreditPurchaseStatus status, Instant cutoff);
}
