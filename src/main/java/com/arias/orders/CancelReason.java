package com.arias.orders;

/** Why an order was cancelled. Stored in {@code orders.cancel_reason}; NULL for orders cancelled before V44. */
public enum CancelReason {
    /** The customer cancelled it from the app. */
    CUSTOMER,
    /** The direct payment was rejected, expired or never registered. */
    PAYMENT_FAILED,
    /** An admin removed a dish of the order from the menu. */
    DISH_REMOVED
}
