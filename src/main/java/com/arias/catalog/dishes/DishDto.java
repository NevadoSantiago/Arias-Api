package com.arias.catalog.dishes;

import com.arias.catalog.categories.CategoryDto;
import com.arias.catalog.menusections.MenuSectionDto;
import com.arias.catalog.sides.SideDto;
import com.arias.catalog.sides.SideType;

import java.util.List;

public record DishDto(
    Long id,
    String nombre,
    String descripcion,
    String fotoUrl,
    CategoryDto category,
    MenuSectionDto menuSection,
    SideType sideType,
    List<SideDto> allowedSides,
    Integer stockActual,
    Boolean especial
) {
    public static DishDto from(Dish d) {
        return new DishDto(
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
            d.getStockActual(),
            d.getEspecial()
        );
    }
}
