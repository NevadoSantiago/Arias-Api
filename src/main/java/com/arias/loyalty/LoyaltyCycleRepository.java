package com.arias.loyalty;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LoyaltyCycleRepository extends JpaRepository<LoyaltyCycle, Long> {

    List<LoyaltyCycle> findByUserIdOrderByStartedAtAsc(Long userId);

    boolean existsByUserId(Long userId);

    /** The cycle with {@code startedAt <= at < endsAt}. */
    @Query("SELECT c FROM LoyaltyCycle c WHERE c.user.id = :userId AND c.startedAt <= :at AND c.endsAt > :at")
    Optional<LoyaltyCycle> findContaining(@Param("userId") Long userId, @Param("at") Instant at);
}
