package com.arias.credits;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditMovementRepository extends JpaRepository<CreditMovement, Long> {

    /** Historial de movimientos de un usuario, más reciente primero (GET /api/v1/credits/movements, unidad 3). */
    List<CreditMovement> findByUserIdOrderByCreatedAtDesc(Long userId);
}
