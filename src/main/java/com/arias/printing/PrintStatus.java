package com.arias.printing;

/** Print state of an order on the kitchen board, aggregated over its latest jobs. */
public enum PrintStatus {
    NONE,
    QUEUED,
    PRINTED,
    FAILED
}
