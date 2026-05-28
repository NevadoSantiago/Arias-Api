package com.arias.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Provee un {@link Clock} inyectable. Usarlo en services (en vez de
 * {@code Instant.now()}) facilita los tests, donde se puede mockear el tiempo.
 *
 * <p>Por default usamos la zona horaria de Argentina, no UTC, porque las reglas
 * de negocio (corte, fecha del día) se interpretan en horario local.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("America/Argentina/Buenos_Aires"));
    }
}
