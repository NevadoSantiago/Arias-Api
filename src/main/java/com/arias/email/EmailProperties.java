package com.arias.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del envío de emails vía Resend.
 *
 * <p>Si {@code apiKey} está vacío (no hay credencial cargada), los envíos
 * se loguean y se descartan — no fallan, no rompen el flujo principal.
 */
@ConfigurationProperties(prefix = "arias.email")
public record EmailProperties(
    String apiKey,
    String from,
    String fromName,
    String appUrl
) {
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Formato "Nombre <email@dominio>" que acepta Resend. */
    public String fromHeader() {
        return "%s <%s>".formatted(fromName, from);
    }
}
