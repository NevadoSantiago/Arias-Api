package com.arias.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /**
     * {@code clearAutomatically = true}: sin esto, una entidad ya cargada en
     * el persistence context (ej. un test que la leyó antes de invalidar)
     * queda con el {@code usedAt} viejo en caché de primer nivel — un
     * {@code findById} posterior en la MISMA transacción devolvería el
     * objeto stale en vez de re-consultar la fila recién actualizada.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE EmailVerificationToken t SET t.usedAt = :now WHERE t.user.id = :userId AND t.usedAt IS NULL")
    int invalidateAllForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
