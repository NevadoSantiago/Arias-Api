package com.arias.restaurantconfig;

import java.time.LocalTime;

public record RestaurantConfigDto(
    LocalTime horaCorte,
    String timezone,
    Integer pickupLeadMinutes,
    Integer creditExpiryDays,
    LocalTime pickupWindowStart,
    LocalTime pickupWindowEnd,
    Integer pickupSlotMinutes,
    LocalTime dailySummaryTime,
    Integer pickupReminderMinutes
) {
    public static RestaurantConfigDto from(RestaurantConfig c) {
        return new RestaurantConfigDto(
            c.getHoraCorte(),
            c.getTimezone(),
            c.getPickupLeadMinutes(),
            c.getCreditExpiryDays(),
            c.getPickupWindowStart(),
            c.getPickupWindowEnd(),
            c.getPickupSlotMinutes(),
            c.getDailySummaryTime(),
            c.getPickupReminderMinutes()
        );
    }
}
