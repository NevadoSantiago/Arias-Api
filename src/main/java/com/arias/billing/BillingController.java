package com.arias.billing;

import com.arias.billing.BillingDtos.BillingPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Reporte de facturación del restaurant. Solo SUPER_ADMIN — acá se ve
 * cuánto factura Arias y a qué precio le vende a cada empresa.
 */
@RestController
@RequestMapping("/api/v1/admin/billing")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BillingController {

    private final BillingService service;

    /**
     * Totales a cobrar por empresa en el rango [desde, hasta] (ambos inclusive).
     * El front por defecto manda la semana cerrada (lunes a domingo).
     *
     * @param companyId opcional — omitirlo devuelve todas las empresas
     */
    @GetMapping
    public BillingPeriod report(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
        @RequestParam(required = false) Long companyId
    ) {
        return service.report(desde, hasta, companyId);
    }
}
