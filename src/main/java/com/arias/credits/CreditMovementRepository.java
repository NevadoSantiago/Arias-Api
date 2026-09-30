package com.arias.credits;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditMovementRepository extends JpaRepository<CreditMovement, Long> {

    /** Historial de movimientos de un usuario, más reciente primero (GET /api/v1/credits/movements, unidad 3). */
    List<CreditMovement> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Pre-check de {@code CreditLedgerService#grantWelcomeLunch} (unidad 5)
     * — evita intentar el INSERT (y por lo tanto chocar contra {@code
     * uq_credit_movement_welcome}) en el caso normal de un reintento
     * secuencial (reenvío de verificación, relogin con Google). El índice
     * único parcial sigue siendo la enforcement real para una carrera
     * verdaderamente concurrente; esto es solo el atajo feliz.
     */
    boolean existsByUserIdAndType(Long userId, MovementType type);
}
