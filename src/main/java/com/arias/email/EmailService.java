package com.arias.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Envío de emails vía Resend HTTP API.
 *
 * <p>Diseño defensivo:
 * <ul>
 *   <li>Si {@link EmailProperties#isConfigured()} es false → solo loggea,
 *       no falla. Permite que el sistema funcione sin Resend configurado.</li>
 *   <li>Si Resend responde error → loggea pero NO re-lanza. Email es
 *       best-effort, no debe bloquear la operación de negocio que lo disparó.</li>
 *   <li>{@code @Async} → no bloquea al caller. Si Resend tarda 2s, el admin
 *       no espera 2s para que termine su request.</li>
 * </ul>
 */
@Service
@Slf4j
public class EmailService {

    private static final String RESEND_API = "https://api.resend.com";

    private final EmailProperties props;
    private final RestClient restClient;

    public EmailService(EmailProperties props) {
        this.props = props;
        this.restClient = RestClient.builder()
            .baseUrl(RESEND_API)
            .build();
    }

    /**
     * Envía un email. Es async — no bloquea al caller.
     * Si falla, loggea el error pero no lanza excepción.
     */
    @Async
    public void send(String toEmail, String subject, String html) {
        if (!props.isConfigured()) {
            log.warn("Email NO enviado (Resend no configurado): to={} subject='{}'", toEmail, subject);
            return;
        }

        Map<String, Object> body = Map.of(
            "from", props.fromHeader(),
            "to", List.of(toEmail),
            "subject", subject,
            "html", html
        );

        try {
            restClient.post()
                .uri("/emails")
                .header("Authorization", "Bearer " + props.apiKey())
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    String respBody = new String(res.getBody().readAllBytes());
                    log.error("Resend devolvió {} al enviar a {}: {}",
                        res.getStatusCode(), toEmail, respBody);
                })
                .toBodilessEntity();
            log.info("Email enviado: to={} subject='{}'", toEmail, subject);
        } catch (Exception e) {
            log.error("Error enviando email a {}: {}", toEmail, e.getMessage());
        }
    }
}
