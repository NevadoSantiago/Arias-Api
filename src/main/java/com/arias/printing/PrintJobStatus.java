package com.arias.printing;

/** Lifecycle of a {@link PrintJob}. CANCELLED = the order was un-comandado before the job was claimed. */
public enum PrintJobStatus {
    PENDING,
    CLAIMED,
    PRINTED,
    FAILED,
    CANCELLED
}
