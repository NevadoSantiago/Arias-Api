package com.arias.orders;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Endpoints administrativos del SUPER_ADMIN sobre el dominio de orders.
 * Usados por el panel del resto para ver el consolidado del día.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminOrderController {

    private final DailyChoiceRepository orderRepo;
    private final OrderService orderService;
    private final OrderExportService orderExportService;
    private final Clock clock;

    /**
     * Consolidado de pedidos del día. Ordenado por hora de entrega
     * (la empresa con corte más temprano se cocina primero) y por empresa.
     *
     * <p>Incluye TODOS los estados — el SUPER_ADMIN ve los PENDIENTE para
     * estimar carga futura, los CONFIRMADO para cocinar, los ENTREGADO para
     * controlar despacho.
     */
    @GetMapping("/today")
    @Transactional(readOnly = true)
    public List<AdminOrderDto> getOrders(@RequestParam(required = false) LocalDate fecha) {
        LocalDate target = fecha != null ? fecha : LocalDate.now(clock);
        return orderRepo.findAllByFechaOrderByCompanyIdAscHoraEntregaAsc(target).stream()
            .map(AdminOrderDto::from)
            .toList();
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportOrders(@RequestParam LocalDate fecha) {
        byte[] excel = orderExportService.exportToExcel(fecha);
        String filename = "pedidos-" + fecha + ".xlsx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(excel);
    }

    @PutMapping("/{id}/comandar")
    public Map<String, Integer> markComandado(@PathVariable Long id) {
        int count = orderService.markComandado(id);
        return Map.of("updated", count);
    }

    @PutMapping("/comandar-company/{companyId}")
    public Map<String, Integer> markComandadoByCompany(@PathVariable Long companyId) {
        int count = orderService.markComandadoByCompany(companyId);
        return Map.of("updated", count);
    }

    @PutMapping("/{id}/deliver")
    public Map<String, Integer> markDelivered(@PathVariable Long id) {
        int count = orderService.markDelivered(id);
        return Map.of("updated", count);
    }

    @PutMapping("/deliver-company/{companyId}")
    public Map<String, Integer> markDeliveredByCompany(@PathVariable Long companyId) {
        int count = orderService.markDeliveredByCompany(companyId);
        return Map.of("updated", count);
    }
}
