package com.arias.billing;

import com.arias.billing.BillingDtos.BillingPeriod;
import com.arias.billing.BillingDtos.CategoryLine;
import com.arias.billing.BillingDtos.CompanyBilling;
import com.arias.billing.BillingDtos.DailyTotal;
import com.arias.common.exception.BusinessException;
import com.arias.orders.DailyChoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reporte de facturación del SUPER_ADMIN: cuánto cobrarle a cada empresa
 * por los pedidos servidos en un período.
 *
 * <p>Los montos salen del precio CONGELADO en cada pedido, no de la tarifa
 * vigente. Renegociar una tarifa no reescribe facturas ya emitidas.
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    /** Tope defensivo: evita que un rango absurdo escanee la tabla entera. */
    private static final int MAX_DIAS = 366;

    private final DailyChoiceRepository orderRepo;

    /**
     * @param companyId null = todas las empresas (el default de la vista).
     *                  Filtrar acá y no en el cliente mantiene una sola fuente
     *                  de verdad para los montos: el total del período siempre
     *                  corresponde a lo que se está mostrando.
     */
    @Transactional(readOnly = true)
    public BillingPeriod report(LocalDate desde, LocalDate hasta, Long companyId) {
        validarRango(desde, hasta);

        List<BillingRow> rows = orderRepo.aggregateBilling(desde, hasta, companyId);
        List<DailyTotal> porDia = orderRepo.aggregateDailyTotals(desde, hasta, companyId).stream()
            .map(r -> new DailyTotal(r.fecha(), r.pedidos(), r.total()))
            .toList();

        // LinkedHashMap: la query ya viene ordenada por nombre de empresa,
        // preservamos ese orden en la respuesta.
        Map<Long, List<BillingRow>> porEmpresa = new LinkedHashMap<>();
        for (BillingRow row : rows) {
            porEmpresa.computeIfAbsent(row.companyId(), k -> new ArrayList<>()).add(row);
        }

        List<CompanyBilling> empresas = porEmpresa.values().stream()
            .map(BillingService::toCompanyBilling)
            .toList();

        long totalGeneral = empresas.stream().mapToLong(CompanyBilling::total).sum();
        long totalPedidos = empresas.stream().mapToLong(CompanyBilling::totalPedidos).sum();
        long sinTarifa = empresas.stream().mapToLong(CompanyBilling::pedidosSinTarifa).sum();

        return new BillingPeriod(
            desde, hasta, totalPedidos, totalGeneral, sinTarifa, porDia, empresas);
    }

    private static CompanyBilling toCompanyBilling(List<BillingRow> rows) {
        List<CategoryLine> lineas = rows.stream()
            .map(r -> new CategoryLine(
                r.displayNombre(),
                r.precioUnitario(),
                r.cantidad(),
                r.subtotal(),
                r.sinTarifa()))
            .toList();

        BillingRow first = rows.getFirst();
        long total = lineas.stream().mapToLong(CategoryLine::subtotal).sum();
        long pedidos = lineas.stream().mapToLong(CategoryLine::cantidad).sum();
        long sinTarifa = lineas.stream()
            .filter(CategoryLine::sinTarifa)
            .mapToLong(CategoryLine::cantidad)
            .sum();

        return new CompanyBilling(
            first.companyId(), first.companyNombre(), pedidos, total, sinTarifa, lineas);
    }

    private static void validarRango(LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null) {
            throw BusinessException.badRequest("billing-range-required",
                "Hay que indicar desde y hasta");
        }
        if (hasta.isBefore(desde)) {
            throw BusinessException.badRequest("billing-range-invalid",
                "La fecha final no puede ser anterior a la inicial");
        }
        if (desde.plusDays(MAX_DIAS).isBefore(hasta)) {
            throw BusinessException.badRequest("billing-range-too-wide",
                "El período no puede superar los " + MAX_DIAS + " días");
        }
    }
}
