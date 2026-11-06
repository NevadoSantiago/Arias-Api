package com.arias.orders;

import java.util.List;

/** Published by the kitchen when orders were marked ENTREGADO; consumed after the transaction commits. */
public record OrderDeliveredEvent(List<Long> orderIds) {
}
