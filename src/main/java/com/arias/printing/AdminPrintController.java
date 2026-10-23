package com.arias.printing;

import com.arias.catalog.dishes.DishStation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Admin endpoints for comanda printing: reprint, printers and the agent status. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminPrintController {

    /** The agent counts as online when it called the API within this time. */
    public static final Duration ONLINE_WINDOW = Duration.ofSeconds(30);

    private final PrintJobService printJobService;
    private final PrinterService printerService;
    private final PrintAgentStatusRepository agentStatusRepo;
    private final Clock clock;

    public record PrintJobDto(Long id, DishStation station, PrintJobStatus status, Instant availableAt) {
        static PrintJobDto from(PrintJob j) {
            return new PrintJobDto(j.getId(), j.getStation(), j.getStatus(), j.getAvailableAt());
        }
    }

    public record PrinterDto(DishStation station, String host, Integer port, Instant updatedAt) {
        static PrinterDto from(Printer p) {
            return new PrinterDto(p.getStation(), p.getHost(), p.getPort(), p.getUpdatedAt());
        }
    }

    public record UpdatePrinterRequest(@NotNull String host, @NotNull Integer port) {}

    public record AgentStatusDto(Instant lastSeenAt, boolean online) {}

    /** Reprints a COMANDADO or ENTREGADO order: same routing as comandar, available immediately. */
    @PostMapping("/orders/{id}/print")
    public List<PrintJobDto> reprint(@PathVariable Long id) {
        return printJobService.reprint(id).stream().map(PrintJobDto::from).toList();
    }

    @GetMapping("/print/printers")
    public List<PrinterDto> listPrinters() {
        return printerService.list().stream().map(PrinterDto::from).toList();
    }

    @PutMapping("/print/printers/{station}")
    public PrinterDto updatePrinter(@PathVariable DishStation station, @Valid @RequestBody UpdatePrinterRequest req) {
        return PrinterDto.from(printerService.update(station, req.host(), req.port()));
    }

    @GetMapping("/print/agent")
    public AgentStatusDto agentStatus() {
        Instant lastSeen = agentStatusRepo.findLastSeenAt().orElse(null);
        boolean online = lastSeen != null && !lastSeen.isBefore(clock.instant().minus(ONLINE_WINDOW));
        return new AgentStatusDto(lastSeen, online);
    }
}
