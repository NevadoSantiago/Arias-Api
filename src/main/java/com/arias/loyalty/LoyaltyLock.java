package com.arias.loyalty;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user serialization of every loyalty mutation (cycle building, reward grants, dessert
 * redemption). A transaction-scoped Postgres advisory lock: re-entrant inside one transaction,
 * released on commit or rollback, and independent of any row lock on {@code users}.
 */
@Component
public class LoyaltyLock {

    /** Namespace of the two-key advisory lock, so loyalty never collides with other advisory users. */
    private static final int NAMESPACE = 0x4C4F5941;

    @PersistenceContext
    private EntityManager em;

    /** Blocks until no other transaction holds the user's loyalty lock; must run inside a transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Long userId) {
        em.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(:ns, :key)")
            .setParameter("ns", NAMESPACE)
            .setParameter("key", Long.hashCode(userId))
            .getSingleResult();
    }
}
