package com.arias.payments;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Pagos del panel admin — {@code SUPER_ADMIN}. Lista las compras de un rango de
 * fechas (inclusivo, en la zona horaria del restaurante) con la comisión real de
 * Mercado Pago, y un resumen con el ingreso neto y el desglose por tipo de compra.
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminPaymentController {

    private final AdminPaymentService service;

    @GetMapping
    public AdminPaymentReportDto list(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "APPROVED") AdminPaymentFilter status) {
        return service.report(from, to, status);
    }
}
