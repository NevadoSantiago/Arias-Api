package com.arias.restaurantconfig;

import java.time.LocalTime;

/** Un día de {@link PickupSchedule}, para {@code GET/PUT} de la franja por día de la semana. */
public record PickupScheduleDayDto(
    Integer dayOfWeek,
    boolean open,
    LocalTime windowStart,
    LocalTime windowEnd
) {
    public static PickupScheduleDayDto from(PickupSchedule s) {
        return new PickupScheduleDayDto(s.getDayOfWeek(), s.isOpen(), s.getWindowStart(), s.getWindowEnd());
    }
}
