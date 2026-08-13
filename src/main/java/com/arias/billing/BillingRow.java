package com.arias.billing;

/**
 * Fila cruda de la agregación de facturación — una por
 * (empresa × categoría × precio congelado).
 *
 * <p>Se agrupa también por {@code precioUnitario} a propósito: si la tarifa se
 * renegoció en medio del período, la misma categoría aparece en dos filas con
 * precios distintos. Eso es correcto contablemente — cada pedido se cobra al
 * precio que estaba acordado cuando se hizo.
 *
 * @param categoryId    null en pedidos anteriores a V14 sin match por nombre
 * @param categoryNombre nombre ACTUAL de la categoría; null si categoryId es null
 * @param categoriaSnapshot nombre congelado en el pedido — fallback para mostrar
 * @param precioUnitario null en pedidos anteriores a V14 (no facturables)
 */
public record BillingRow(
    Long companyId,
    String companyNombre,
    Long categoryId,
    String categoryNombre,
    String categoriaSnapshot,
    Integer precioUnitario,
    Long cantidad
) {
    /** Nombre a mostrar: el actual si la categoría sigue existiendo, si no el congelado. */
    public String displayNombre() {
        return categoryNombre != null ? categoryNombre : categoriaSnapshot;
    }

    /** Un pedido sin tarifa no se está cobrando — el reporte lo expone como alerta. */
    public boolean sinTarifa() {
        return precioUnitario == null || precioUnitario == 0;
    }

    public long subtotal() {
        return precioUnitario == null ? 0L : (long) precioUnitario * cantidad;
    }
}
