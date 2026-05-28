package com.arias.restaurantconfig;

import java.time.LocalDate;

public record DisabledDateDto(LocalDate fecha, String motivo) {
    public static DisabledDateDto from(FechaDeshabilitada f) {
        return new DisabledDateDto(f.getFecha(), f.getMotivo());
    }
}
