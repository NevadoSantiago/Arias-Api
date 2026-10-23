package com.arias.printing;

import java.time.Instant;

/** Aggregated print state of one order; {@code printedAt} is set only when the status is PRINTED. */
public record PrintSummary(PrintStatus status, Instant printedAt) {

    public static final PrintSummary NONE = new PrintSummary(PrintStatus.NONE, null);
}
