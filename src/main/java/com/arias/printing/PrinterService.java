package com.arias.printing;

import com.arias.catalog.dishes.DishStation;
import com.arias.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Admin view and edit of the station printers. The host must be a private IPv4 literal. */
@Service
@RequiredArgsConstructor
public class PrinterService {

    private final PrinterRepository repo;

    @Transactional(readOnly = true)
    public List<Printer> list() {
        return repo.findAll().stream()
            .sorted((a, b) -> a.getStation().compareTo(b.getStation()))
            .toList();
    }

    @Transactional
    public Printer update(DishStation station, String host, Integer port) {
        String cleanHost = host == null ? "" : host.trim();
        if (!PrivateIpv4.isPrivate(cleanHost)) {
            throw BusinessException.badRequest("printer-host-invalid",
                "La IP de la ticketera tiene que ser una IPv4 privada (10.x.x.x, 172.16-31.x.x o 192.168.x.x)");
        }
        if (port == null || port < 1 || port > 65535) {
            throw BusinessException.badRequest("printer-port-invalid",
                "El puerto de la ticketera tiene que estar entre 1 y 65535");
        }
        Printer printer = repo.findById(station)
            .orElseThrow(() -> BusinessException.notFound("printer-not-found", "Ticketera no encontrada"));
        printer.setHost(cleanHost);
        printer.setPort(port);
        return printer;
    }
}
