package com.arias.catalog.desserts;

/** Postre para el panel admin: incluye el stock por defecto y {@code enabled}. */
public record AdminDessertDto(
    Long id,
    String nombre,
    String descripcion,
    String fotoUrl,
    Integer stockDiarioDefault,
    Integer stockActual,
    Boolean enabled
) {
    public static AdminDessertDto from(Dessert d) {
        return new AdminDessertDto(d.getId(), d.getNombre(), d.getDescripcion(), d.getFotoUrl(),
            d.getStockDiarioDefault(), d.getStockActual(), d.getEnabled());
    }
}
