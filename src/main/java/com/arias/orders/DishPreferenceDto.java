package com.arias.orders;

public record DishPreferenceDto(
    Long sideId,
    String sideNombre,
    String notas
) {
    public static DishPreferenceDto from(DailyChoice c) {
        return new DishPreferenceDto(
            c.getSide() != null ? c.getSide().getId() : null,
            c.getSideNombre(),
            c.getNotas()
        );
    }
}
