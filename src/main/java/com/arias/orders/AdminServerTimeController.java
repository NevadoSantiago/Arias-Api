package com.arias.orders;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Server clock for the kitchen board, so its countdowns do not depend on the
 * admin PC clock. A separate endpoint keeps the {@code /by-pickup} JSON shape
 * untouched.
 */
@RestController
@RequestMapping("/api/v1/admin/server-time")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminServerTimeController {

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final Clock clock;

    public record ServerTimeResponse(OffsetDateTime now) {}

    @GetMapping
    public ServerTimeResponse serverTime() {
        return new ServerTimeResponse(OffsetDateTime.now(clock.withZone(ZONE)));
    }
}
