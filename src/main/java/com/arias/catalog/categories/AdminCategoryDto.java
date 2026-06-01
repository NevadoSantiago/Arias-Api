package com.arias.catalog.categories;

public record AdminCategoryDto(
    Long id,
    String nombre,
    Long parentId,
    String parentNombre,
    Integer ordenDisplay,
    Boolean enabled
) {
    public static AdminCategoryDto from(Category c) {
        return new AdminCategoryDto(
            c.getId(),
            c.getNombre(),
            c.getParent() != null ? c.getParent().getId() : null,
            c.getParent() != null ? c.getParent().getNombre() : null,
            c.getOrdenDisplay(),
            c.getEnabled()
        );
    }
}
