package com.arias.restaurantconfig;

import java.time.LocalTime;

public record RestaurantConfigDto(
    LocalTime horaCorte,
    String timezone
) {
    public static RestaurantConfigDto from(RestaurantConfig c) {
        return new RestaurantConfigDto(c.getHoraCorte(), c.getTimezone());
    }
}
