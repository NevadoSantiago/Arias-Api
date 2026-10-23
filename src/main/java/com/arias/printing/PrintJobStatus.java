package com.arias.printing;

/**
 * Lifecycle of a {@link PrintJob}. CANCELLED = the order was un-comandado before the job was claimed.
 * EXPIRED = not printed within the job TTL of being enqueued; terminal, never printed (the admin can reprint).
 */
public enum PrintJobStatus {
    PENDING,
    CLAIMED,
    PRINTED,
    FAILED,
    CANCELLED,
    EXPIRED
}
