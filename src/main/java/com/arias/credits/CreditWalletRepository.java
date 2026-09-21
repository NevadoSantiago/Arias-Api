package com.arias.credits;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreditWalletRepository extends JpaRepository<CreditWallet, Long> {

    /**
     * {@code SELECT ... FOR UPDATE} sobre la billetera de un usuario. Único
     * punto de lectura antes de mutar saldo — el bloqueo de esta fila (no un
     * {@code @Version} optimista) es lo que evita el sobregiro cuando dos
     * pedidos del mismo usuario intentan comprometer crédito en paralelo
     * (ver diseño §Decisión 3, y {@code CreditLedgerService.apply(...)} en
     * la unidad 3, único método que debe llamar a este repo).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM CreditWallet w WHERE w.userId = :userId")
    Optional<CreditWallet> findByIdForUpdate(@Param("userId") Long userId);
}
