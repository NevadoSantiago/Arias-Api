package com.arias.credits.discount;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AccountDiscountRepository extends JpaRepository<AccountDiscount, Long> {

    Optional<AccountDiscount> findByUserId(Long userId);

    /** {@code SELECT ... FOR UPDATE}: reserving, releasing and consuming serialize on this row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM AccountDiscount d WHERE d.user.id = :userId")
    Optional<AccountDiscount> findByUserIdForUpdate(@Param("userId") Long userId);

    /**
     * Race-free grant: the UNIQUE {@code user_id} makes a concurrent second insert a no-op
     * (returns 0) instead of a constraint violation that would poison the transaction.
     */
    @Modifying
    @Query(value = """
        INSERT INTO account_discount (user_id, percent, granted_at, expires_at, source)
        VALUES (:userId, :percent, :grantedAt, :expiresAt, :source)
        ON CONFLICT (user_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("percent") int percent,
                       @Param("grantedAt") Instant grantedAt, @Param("expiresAt") Instant expiresAt,
                       @Param("source") String source);
}
