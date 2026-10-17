package com.arias.users.notifications;

import com.arias.email.EmailContent;
import com.arias.email.EmailLayout;
import com.arias.email.EmailProperties;
import com.arias.email.EmailService;
import com.arias.email.OutgoingEmail;
import com.arias.users.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Template HTML del recordatorio diario "¿te olvidaste tu almuerzo?".
 * Sigue el estilo visual de los otros mails (rojo bodegón + serif).
 */
@Component
@RequiredArgsConstructor
public class OrderReminderEmail {

    private final EmailService emailService;
    private final EmailProperties props;
    private final ReminderUnsubscribeToken tokens;

    private static final String SUBJECT = "¿Te olvidaste tu almuerzo de hoy?";

    public void send(User user) {
        emailService.send(user.getEmail(), SUBJECT, buildHtml(user));
    }

    /** Recordatorios en una sola request a Resend (batch) — evita el rate limit de 2 req/s. */
    public void sendBatch(List<User> users) {
        List<OutgoingEmail> batch = users.stream()
            .map(u -> new OutgoingEmail(u.getEmail(), SUBJECT, buildHtml(u)))
            .toList();
        emailService.sendBatch(batch);
    }

    private String buildHtml(User user) {
        String firstName = safeName(user.getFirstName());
        String token = tokens.generate(user.getId());
        String unsubscribeUrl = props.appUrl() + "/unsubscribe-reminder?t=" + token;
        String menuUrl = props.appUrl() + "/orders/today";

        String body = EmailLayout.paragraph("Vimos que todavía no elegiste tu almuerzo de hoy. ¡Hay opciones esperándote!")
            + EmailLayout.paragraph("Entrá a la app y armá tu pedido antes del cierre.");
        String unsubscribe = "<a href=\"" + EmailLayout.escape(unsubscribeUrl)
            + "\" style=\"color:#66524B; text-decoration:underline;\">No quiero recibir más estos avisos</a>";

        return EmailLayout.render(EmailContent.of("Hola " + firstName, body)
            .withCta("Ver menú", menuUrl)
            .withBelowFooter(unsubscribe));
    }

    private static String safeName(String name) {
        return (name != null && !name.isBlank()) ? name : "che";
    }
}
