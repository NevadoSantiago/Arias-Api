package com.arias.catalog.categories;

import java.util.Map;

public record AdminCategoryDto(
    Long id,
    String nombre,
    Long parentId,
    String parentNombre,
    Integer ordenDisplay,
    Boolean enabled,
    /** Map companyId → precio en pesos sin decimales. */
    Map<Long, Integer> companyPrices
) {
    public static AdminCategoryDto from(Category c, Map<Long, Integer> companyPrices) {
        return new AdminCategoryDto(
            c.getId(),
            c.getNombre(),
            c.getParent() != null ? c.getParent().getId() : null,
            c.getParent() != null ? c.getParent().getNombre() : null,
            c.getOrdenDisplay(),
            c.getEnabled(),
            companyPrices
        );
    }
}
