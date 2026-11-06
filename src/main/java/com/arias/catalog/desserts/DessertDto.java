package com.arias.catalog.desserts;

/** Postre visible para el cliente; {@code stockActual == 0} se muestra como "Sin stock hoy". */
public record DessertDto(
    Long id,
    String nombre,
    String descripcion,
    String fotoUrl,
    Integer stockActual
) {
    public static DessertDto from(Dessert d) {
        return new DessertDto(d.getId(), d.getNombre(), d.getDescripcion(), d.getFotoUrl(), d.getStockActual());
    }
}
