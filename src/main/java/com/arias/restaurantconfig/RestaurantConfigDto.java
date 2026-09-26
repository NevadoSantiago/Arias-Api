package com.arias.restaurantconfig;

import java.time.LocalTime;
import java.util.List;

public record RestaurantConfigDto(
    LocalTime horaCorte,
    String timezone,
    Integer pickupLeadMinutes,
    Integer creditExpiryDays,
    /** Deprecated: superseded by {@link #pickupSchedule()} (migración V24, B5/F14) — se mantiene solo por compatibilidad. */
    LocalTime pickupWindowStart,
    /** Deprecated: superseded by {@link #pickupSchedule()} (migración V24, B5/F14) — se mantiene solo por compatibilidad. */
    LocalTime pickupWindowEnd,
    Integer pickupSlotMinutes,
    LocalTime dailySummaryTime,
    Integer pickupReminderMinutes,
    /** Franja de retiro por día ISO de la semana (1=lunes..7=domingo), ordenada lunes a domingo. */
    List<PickupScheduleDayDto> pickupSchedule
) {
    public static RestaurantConfigDto from(RestaurantConfig c, List<PickupScheduleDayDto> pickupSchedule) {
        return new RestaurantConfigDto(
            c.getHoraCorte(),
            c.getTimezone(),
            c.getPickupLeadMinutes(),
            c.getCreditExpiryDays(),
            c.getPickupWindowStart(),
            c.getPickupWindowEnd(),
            c.getPickupSlotMinutes(),
            c.getDailySummaryTime(),
            c.getPickupReminderMinutes(),
            pickupSchedule
        );
    }
}
