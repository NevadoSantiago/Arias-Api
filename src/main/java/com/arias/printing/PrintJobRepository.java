package com.arias.printing;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PrintJobRepository extends JpaRepository<PrintJob, Long> {

    @Query("SELECT j FROM PrintJob j WHERE j.order.id = :orderId ORDER BY j.id")
    List<PrintJob> findByOrderId(@Param("orderId") Long orderId);

    /**
     * Cancels the jobs of an order that are still PENDING (never claimed). Claimed, printed or
     * failed jobs are left alone. Bulk UPDATE: it flushes first but does not clear the context (the
     * caller still holds the Order), so entities loaded earlier may be stale until refreshed.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PrintJob j SET j.status = com.arias.printing.PrintJobStatus.CANCELLED, j.updatedAt = :now "
        + "WHERE j.order.id = :orderId AND j.status = com.arias.printing.PrintJobStatus.PENDING")
    int cancelPendingForOrder(@Param("orderId") Long orderId, @Param("now") Instant now);

    /**
     * Locks, with {@code SELECT ... FOR UPDATE SKIP LOCKED}, up to {@code page.size} jobs the agent
     * may take: PENDING and already available, or CLAIMED for too long (re-offer), and in both cases
     * enqueued no earlier than {@code expireBefore} (older jobs are never offered). Rows another
     * claim holds locked are skipped, so two agents never get the same job.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT j FROM PrintJob j WHERE j.createdAt >= :expireBefore AND ("
        + "(j.status = com.arias.printing.PrintJobStatus.PENDING AND j.availableAt <= :now) "
        + "OR (j.status = com.arias.printing.PrintJobStatus.CLAIMED AND j.claimedAt <= :staleBefore)) "
        + "ORDER BY j.availableAt, j.id")
    List<PrintJob> lockClaimable(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore,
                                 @Param("expireBefore") Instant expireBefore, Pageable page);

    /**
     * Expires the jobs enqueued before {@code expireBefore} that would otherwise be offered: PENDING
     * ones and CLAIMED ones whose claim is stale ({@code claimedAt <= staleBefore}). A job claimed
     * recently is left to its ack. Terminal, so a restarted agent never prints old tickets.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PrintJob j SET j.status = com.arias.printing.PrintJobStatus.EXPIRED, j.updatedAt = :now "
        + "WHERE j.createdAt < :expireBefore AND ("
        + "j.status = com.arias.printing.PrintJobStatus.PENDING "
        + "OR (j.status = com.arias.printing.PrintJobStatus.CLAIMED AND j.claimedAt <= :staleBefore))")
    int expireOverdue(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore,
                      @Param("expireBefore") Instant expireBefore);

    /**
     * Jobs claimed long ago whose next re-offer would reach the attempt limit: they end FAILED
     * instead of printing forever when the agent never acknowledges them.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PrintJob j SET j.status = com.arias.printing.PrintJobStatus.FAILED, "
        + "j.attempts = j.attempts + 1, j.lastError = :error, j.updatedAt = :now "
        + "WHERE j.status = com.arias.printing.PrintJobStatus.CLAIMED AND j.claimedAt <= :staleBefore "
        + "AND j.attempts + 1 >= :maxAttempts")
    int failExhaustedStaleClaims(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore,
                                 @Param("maxAttempts") int maxAttempts, @Param("error") String error);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM PrintJob j WHERE j.id = :id")
    Optional<PrintJob> findByIdForUpdate(@Param("id") Long id);

    /** Lightweight rows (no ESC/POS bytes) for the print status of the kitchen board. */
    @Query("SELECT new com.arias.printing.PrintJobInfo(j.order.id, j.id, j.station, j.status, j.printedAt) "
        + "FROM PrintJob j WHERE j.order.id IN :orderIds AND j.status <> com.arias.printing.PrintJobStatus.CANCELLED")
    List<PrintJobInfo> findInfoByOrderIds(@Param("orderIds") Collection<Long> orderIds);
}
