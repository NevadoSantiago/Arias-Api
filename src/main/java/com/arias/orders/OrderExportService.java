package com.arias.orders;

import com.arias.common.exception.BusinessException;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrderExportService {

    private static final Set<OrderEstado> EXPORTABLE_ESTADOS =
        EnumSet.of(OrderEstado.CONFIRMADO, OrderEstado.COMANDADO, OrderEstado.ENTREGADO);

    private final DailyChoiceRepository orderRepo;
    private final CompanyRepository companyRepo;

    /**
     * Exporta a Excel los pedidos de una empresa para una fecha dada.
     * Solo incluye pedidos en estados {@link #EXPORTABLE_ESTADOS} —
     * los PENDIENTE no se exportan porque significan que el corte aún no pasó.
     */
    @Transactional(readOnly = true)
    public byte[] exportCompanyToExcel(Long companyId, LocalDate fecha) {
        Company company = companyRepo.findById(companyId)
            .orElseThrow(() -> BusinessException.notFound("company-not-found",
                "Empresa no encontrada"));

        List<DailyChoice> orders = orderRepo
            .findAllByCompanyIdAndFechaOrderByHoraEntregaAsc(companyId, fecha)
            .stream()
            .filter(o -> EXPORTABLE_ESTADOS.contains(o.getEstado()))
            .sorted(Comparator
                .comparing(DailyChoice::getDishNombre)
                .thenComparing(o -> o.getSideNombre() != null ? o.getSideNombre() : "")
                .thenComparing(this::buildFullName))
            .toList();

        if (orders.isEmpty()) {
            throw BusinessException.badRequest("no-orders",
                "No hay pedidos confirmados para exportar de esta empresa");
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle totalStyle = createTotalStyle(workbook);

            Sheet sheet = workbook.createSheet(sanitizeSheetName(company.getNombre()));

            String[] headers = {"Nombre y Apellido", "Plato", "Acompañamiento", "Notas", "Comandado"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            String lastDish = null;

            for (DailyChoice order : orders) {
                if (lastDish != null && !lastDish.equals(order.getDishNombre())) {
                    rowNum++;
                }
                lastDish = order.getDishNombre();

                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(buildFullName(order));
                row.createCell(1).setCellValue(order.getDishNombre());
                row.createCell(2).setCellValue(order.getSideNombre() != null ? order.getSideNombre() : "");
                row.createCell(3).setCellValue(order.getNotas() != null ? order.getNotas() : "");
                row.createCell(4).setCellValue(false);
            }

            // Fila TOTAL al final
            rowNum++;
            Row totalRow = sheet.createRow(rowNum);
            Cell totalLabel = totalRow.createCell(0);
            totalLabel.setCellValue("TOTAL:");
            totalLabel.setCellStyle(totalStyle);
            Cell totalValue = totalRow.createCell(1);
            totalValue.setCellValue(orders.size());
            totalValue.setCellStyle(totalStyle);

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Error generando Excel", e);
        }
    }

    private String buildFullName(DailyChoice order) {
        String first = order.getUser().getFirstName();
        String last = order.getUser().getLastName();
        String name = "";
        if (first != null) name += first;
        if (last != null) name += " " + last;
        return name.trim();
    }

    private CellStyle createHeaderStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();

        byte[] red = {(byte) 0xc5, 0x19, 0x1d};
        XSSFFont font = workbook.createFont();
        font.setBold(true);
        font.setColor(new XSSFColor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, null));
        style.setFont(font);

        ((org.apache.poi.xssf.usermodel.XSSFCellStyle) style)
            .setFillForegroundColor(new XSSFColor(red, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        return style;
    }

    private CellStyle createTotalStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        XSSFFont font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setBorderTop(BorderStyle.THIN);
        return style;
    }

    private String sanitizeSheetName(String name) {
        String sanitized = name.replaceAll("[\\\\/?*\\[\\]]", "_");
        return sanitized.length() > 31 ? sanitized.substring(0, 31) : sanitized;
    }
}
