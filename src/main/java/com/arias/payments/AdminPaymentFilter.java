package com.arias.payments;

/** Which purchases the admin payments report lists as rows. */
public enum AdminPaymentFilter {
    /** Payments made: purchases that ended up approved. */
    APPROVED,
    /** Every purchase in the range, whatever its status. */
    ALL
}
