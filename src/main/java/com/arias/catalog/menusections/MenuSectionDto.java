package com.arias.catalog.menusections;

public record MenuSectionDto(
    Long id,
    String nombre,
    Integer ordenDisplay,
    Boolean enabled
) {
    public static MenuSectionDto from(MenuSection s) {
        return new MenuSectionDto(s.getId(), s.getNombre(), s.getOrdenDisplay(), s.getEnabled());
    }
}
