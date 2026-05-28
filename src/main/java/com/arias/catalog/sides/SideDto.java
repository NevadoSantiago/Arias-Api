package com.arias.catalog.sides;

public record SideDto(
    Long id,
    String nombre,
    SideType tipo,
    Boolean enabled
) {
    public static SideDto from(Side s) {
        return new SideDto(s.getId(), s.getNombre(), s.getTipo(), s.getEnabled());
    }
}
