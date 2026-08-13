package com.arias.billing;

import java.time.LocalDate;
import java.util.List;

/** DTOs de salida del reporte de facturación. Montos en pesos, sin decimales. */
public final class BillingDtos {

    private BillingDtos() {}

    /**
     * Una línea de la factura: "12 × Premium a $8000 = $96000".
     *
     * @param sinTarifa true si no hay precio acordado cargado — esos pedidos
     *                  se sirvieron y no se están cobrando
     */
    public record CategoryLine(
        String categoria,
        Integer precioUnitario,
        long cantidad,
        long subtotal,
        boolean sinTarifa
    ) {}

    /** Total a cobrarle a una empresa en el período, con su desglose por categoría. */
    public record CompanyBilling(
        Long companyId,
        String companyNombre,
        long totalPedidos,
        long total,
        long pedidosSinTarifa,
        List<CategoryLine> lineas
    ) {}

    /** Total de un día del período. Solo días con pedidos servidos. */
    public record DailyTotal(
        LocalDate fecha,
        long pedidos,
        long total
    ) {}

    /**
     * Respuesta completa del período.
     *
     * @param pedidosSinTarifa total global de pedidos servidos sin precio cargado.
     *                         Si es > 0, el reporte está subfacturando.
     * @param porDia evolución diaria. Suma exactamente lo mismo que {@code empresas} —
     *               son dos cortes de los mismos pedidos.
     */
    public record BillingPeriod(
        LocalDate desde,
        LocalDate hasta,
        long totalPedidos,
        long totalGeneral,
        long pedidosSinTarifa,
        List<DailyTotal> porDia,
        List<CompanyBilling> empresas
    ) {}
}
