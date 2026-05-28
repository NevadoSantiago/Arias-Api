package com.arias.catalog.dishes;

import com.arias.catalog.categories.CategoryDto;
import com.arias.catalog.menusections.MenuSectionDto;
import com.arias.catalog.sides.SideDto;
import com.arias.catalog.sides.SideType;

import java.util.List;
import java.util.Set;

/**
 * Versión enriquecida del DTO de Dish para el panel admin.
 * Incluye stock_diario_default y enabled, que no se exponen al empleado.
 */
public record AdminDishDto(
    Long id,
    String nombre,
    String descripcion,
    String fotoUrl,
    CategoryDto category,
    MenuSectionDto menuSection,
    SideType sideType,
    List<SideDto> allowedSides,
    Integer stockDiarioDefault,
    Integer stockActual,
    Boolean enabled,
    Boolean especial,
    Set<DiaSemana> diasSemana
) {
    public static AdminDishDto from(Dish d) {
        return new AdminDishDto(
            d.getId(),
            d.getNombre(),
            d.getDescripcion(),
            d.getFotoUrl(),
            CategoryDto.from(d.getCategory()),
            MenuSectionDto.from(d.getMenuSection()),
            d.getSideType(),
            d.getAllowedSides().stream()
                .map(SideDto::from)
                .sorted((a, b) -> a.nombre().compareToIgnoreCase(b.nombre()))
                .toList(),
            d.getStockDiarioDefault(),
            d.getStockActual(),
            d.getEnabled(),
            d.getEspecial(),
            d.getDiasSemana()
        );
    }
}
