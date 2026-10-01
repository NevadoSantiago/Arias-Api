package com.arias.payments;

import java.util.List;

/**
 * Totals of the report range. Income figures (approvedCount, gross, fee, net)
 * only count APPROVED purchases; feeCents and netCents add up the purchases that
 * have them, and rowsWithoutFee counts the approved ones that do not (older
 * purchases, or Mercado Pago did not report it). IN_MEDIATION and REVERSED are
 * counted apart and never summed into income.
 */
public record AdminPaymentSummaryDto(
    long approvedCount,
    long grossCents,
    long feeCents,
    long netCents,
    long rowsWithoutFee,
    List<KindBreakdown> byKind,
    long inMediationCount,
    long reversedCount
) {

    /** Approved purchases of one kind, for the pie chart. */
    public record KindBreakdown(String kind, long count, long grossCents) {}
}
