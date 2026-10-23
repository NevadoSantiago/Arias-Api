package com.arias.printing;

import com.arias.catalog.dishes.DishStation;

import java.time.Instant;

/** Projection of a print job without its bytes: what the board needs to compute the print status. */
public record PrintJobInfo(Long orderId, Long jobId, DishStation station, PrintJobStatus status, Instant printedAt) {
}
