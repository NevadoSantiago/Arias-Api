package com.arias.printing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

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
}
