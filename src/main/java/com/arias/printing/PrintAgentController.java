package com.arias.printing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API of the local print agent. Authenticated by the {@code X-Print-Agent-Key} header through its
 * own security chain (see {@code PrintAgentSecurityConfig}); there is no JWT here.
 */
@RestController
@RequestMapping("/api/v1/print-agent")
@RequiredArgsConstructor
public class PrintAgentController {

    private final PrintAgentService agentService;

    public record AckRequest(@NotNull AckStatus status, String error, @NotBlank String claimToken) {}

    /** Claims the available jobs. Also the agent heartbeat, so call it on every polling cycle. */
    @PostMapping("/jobs/claim")
    public List<PrintAgentService.AgentJobDto> claim() {
        return agentService.claim();
    }

    @PostMapping("/jobs/{id}/ack")
    public PrintAgentService.AckResult ack(@PathVariable Long id, @Valid @RequestBody AckRequest req) {
        return agentService.ack(id, req.status(), req.error(), req.claimToken());
    }
}
