package com.arias.orders;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderExportService {

    private final DailyChoiceRepository orderRepo;

    @Transactional(readOnly = true)
    public byte[] exportToExcel(LocalDate fecha) {
        List<DailyChoice> orders = orderRepo.findAllByFechaOrderByCompanyIdAscHoraEntregaAsc(fecha);

        Map<String, List<DailyChoice>> byCompany = orders.stream()
            .collect(Collectors.groupingBy(
                o -> o.getCompany().getNombre(),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);

            for (var entry : byCompany.entrySet()) {
                List<DailyChoice> companyOrders = entry.getValue();

                companyOrders.sort(Comparator
                    .comparing(DailyChoice::getDishNombre)
                    .thenComparing(o -> o.getSideNombre() != null ? o.getSideNombre() : "")
                    .thenComparing(o -> buildFullName(o)));

                Sheet sheet = workbook.createSheet(sanitizeSheetName(entry.getKey()));

                String[] headers = {"Nombre y Apellido", "Plato", "Acompañamiento", "Notas", "Comandado"};
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }

                int rowNum = 1;
                String lastDish = null;

                for (DailyChoice order : companyOrders) {
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

                for (int i = 0; i < headers.length; i++) {
                    sheet.autoSizeColumn(i);
                }
            }

            if (byCompany.isEmpty()) {
                workbook.createSheet("Sin pedidos");
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

    private String sanitizeSheetName(String name) {
        String sanitized = name.replaceAll("[\\\\/?*\\[\\]]", "_");
        return sanitized.length() > 31 ? sanitized.substring(0, 31) : sanitized;
    }
}
