package com.arias.printing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PrintAgentStatusRepository extends JpaRepository<PrintAgentStatus, Short> {

    /** Records the heartbeat. A bulk UPDATE so concurrent claims never conflict on an entity version. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PrintAgentStatus s SET s.lastSeenAt = :now WHERE s.id = 1")
    int touch(@Param("now") Instant now);

    /** Scalar read: always hits the database, never a stale cached entity. */
    @Query("SELECT s.lastSeenAt FROM PrintAgentStatus s WHERE s.id = 1")
    Optional<Instant> findLastSeenAt();
}
