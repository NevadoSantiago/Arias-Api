package com.arias.orders;

import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Endpoints administrativos del SUPER_ADMIN sobre el dominio de orders.
 * Usados por el panel del resto para ver el consolidado del día.
 *
 * <p>Unidad 13 (spec {@code admin-order-fulfillment}) agrega la vista y
 * exportación agrupadas por horario de retiro, que leen {@code orders} vía
 * {@link OrderRepository} — la vista por empresa de arriba sigue intacta y
 * sigue leyendo {@code daily_choice} vía {@link DailyChoiceRepository}. Son
 * dos fuentes de datos distintas que nunca se mezclan en un mismo endpoint.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminOrderController {

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final DailyChoiceRepository orderRepo;
    private final OrderRepository orderRepository;
    private final CompanyRepository companyRepo;
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

    /**
     * Exporta los pedidos CONFIRMADOS de una empresa para una fecha en .xlsx.
     * Solo incluye estados post-corte (CONFIRMADO/COMANDADO/ENTREGADO).
     *
     * <p>Side effect: al exportar, los pedidos CONFIRMADO de esa empresa pasan a
     * COMANDADO automáticamente. El admin no necesita un botón aparte — exportar
     * el Excel ES la confirmación de que el pedido se cargó en cocina.
     */
    @GetMapping("/export/{companyId}")
    @Transactional
    public ResponseEntity<byte[]> exportCompanyOrders(
        @PathVariable Long companyId,
        @RequestParam LocalDate fecha
    ) {
        Company company = companyRepo.findById(companyId)
            .orElseThrow(() -> BusinessException.notFound("company-not-found",
                "Empresa no encontrada"));

        // Marcar como COMANDADO antes de generar el Excel — si falla el Excel,
        // la transacción rollbackea y el estado queda como estaba.
        orderService.markComandadoByCompany(companyId, fecha);

        byte[] excel = orderExportService.exportCompanyToExcel(companyId, fecha);
        String safeName = slugify(company.getNombre());
        String filename = "pedidos-" + safeName + "-" + fecha + ".xlsx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(excel);
    }

    @PutMapping("/deliver-company/{companyId}")
    public Map<String, Integer> markDeliveredByCompany(@PathVariable Long companyId) {
        int count = orderService.markDeliveredByCompany(companyId);
        return Map.of("updated", count);
    }

    /**
     * Consolidado de pedidos del día (tabla {@code orders}) agrupado por
     * horario de retiro, legible por cocina — unidad 13, spec {@code
     * admin-order-fulfillment}. Excluye {@code CANCELADO}, mismo criterio que
     * el resumen matutino de {@code OrderNotificationScheduler}: el resto de
     * la app lo trata como si no existiera.
     */
    @GetMapping("/by-pickup")
    @Transactional(readOnly = true)
    public List<AdminOrderDto.PickupGroupDto> getOrdersByPickup(@RequestParam(required = false) LocalDate fecha) {
        LocalDate target = fecha != null ? fecha : LocalDate.now(clock);
        List<Order> orders = orderRepository.findByFechaAndEstadoNotOrderByPickupAtAsc(target, OrderEstado.CANCELADO);
        return AdminOrderDto.PickupGroupDto.groupByPickup(orders, ZONE);
    }

    /**
     * Exporta a .xlsx los pedidos del día agrupados por horario de retiro —
     * unidad 13, spec {@code admin-order-fulfillment}. A diferencia de {@link
     * #exportCompanyOrders}, no tiene side effect de cambio de estado: la
     * confirmación de "cargado en cocina" para pedidos B2C queda fuera de
     * alcance de esta unidad.
     */
    @GetMapping("/export/by-pickup")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportOrdersByPickup(@RequestParam LocalDate fecha) {
        byte[] excel = orderExportService.exportByPickupToExcel(fecha);
        String filename = "pedidos-retiro-" + fecha + ".xlsx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(excel);
    }

    /** Quita tildes y caracteres raros del nombre de la empresa para el filename. */
    private static String slugify(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return normalized
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }
}
