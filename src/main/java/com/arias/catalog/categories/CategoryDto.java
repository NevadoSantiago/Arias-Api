package com.arias.catalog.categories;

public record CategoryDto(
    Long id,
    String nombre,
    Long parentId
) {
    public static CategoryDto from(Category c) {
        return new CategoryDto(
            c.getId(),
            c.getNombre(),
            c.getParent() != null ? c.getParent().getId() : null
        );
    }
}
