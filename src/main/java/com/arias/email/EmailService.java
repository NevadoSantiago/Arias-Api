package com.arias.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
 *   <li>{@code @Async("emailExecutor")} → no bloquea al caller y serializa
 *       los envíos en UN solo thread: Resend limita a 2 req/s, en paralelo
 *       los excedentes reciben 429.</li>
 *   <li>429 → retry con backoff. Cualquier otro error se loggea y descarta.</li>
 * </ul>
 */
@Service
@Slf4j
public class EmailService {

    private static final String RESEND_API = "https://api.resend.com";
    /** Resend acepta hasta 100 mails por request a /emails/batch. */
    private static final int BATCH_LIMIT = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1100;

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
    @Async("emailExecutor")
    public void send(String toEmail, String subject, String html) {
        if (!props.isConfigured()) {
            log.warn("Email NO enviado (Resend no configurado): to={} subject='{}'", toEmail, subject);
            return;
        }
        postWithRetry("/emails", emailBody(toEmail, subject, html),
            "to=" + toEmail + " subject='" + subject + "'");
    }

    /**
     * Envía varios emails en una sola request ({@code POST /emails/batch},
     * hasta 100 por request — se trocea solo si hace falta). Para altas
     * masivas: N mails cuentan como UNA request contra el rate limit.
     */
    @Async("emailExecutor")
    public void sendBatch(List<OutgoingEmail> emails) {
        if (emails.isEmpty()) {
            return;
        }
        if (!props.isConfigured()) {
            log.warn("Batch de {} emails NO enviado (Resend no configurado)", emails.size());
            return;
        }
        for (int i = 0; i < emails.size(); i += BATCH_LIMIT) {
            List<OutgoingEmail> chunk = emails.subList(i, Math.min(i + BATCH_LIMIT, emails.size()));
            List<Map<String, Object>> body = chunk.stream()
                .map(e -> emailBody(e.to(), e.subject(), e.html()))
                .toList();
            postWithRetry("/emails/batch", body, "batch de " + chunk.size() + " emails");
        }
    }

    private Map<String, Object> emailBody(String toEmail, String subject, String html) {
        return Map.of(
            "from", props.fromHeader(),
            "to", List.of(toEmail),
            "subject", subject,
            "html", html
        );
    }

    private void postWithRetry(String uri, Object body, String description) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restClient.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + props.apiKey())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
                log.info("Email enviado: {}", description);
                return;
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 429 && attempt < MAX_ATTEMPTS) {
                    log.warn("Resend rate limit (429) para {} — reintento {}/{}",
                        description, attempt, MAX_ATTEMPTS - 1);
                    sleep(RETRY_DELAY_MS * attempt);
                    continue;
                }
                log.error("Resend devolvió {} para {}: {}",
                    e.getStatusCode(), description, e.getResponseBodyAsString());
                return;
            } catch (Exception e) {
                log.error("Error enviando email ({}): {}", description, e.getMessage());
                return;
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
