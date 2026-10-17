package com.arias.email;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mails de bienvenida / activación de cuenta. Se disparan cuando se asocia
 * un nuevo usuario al sistema:
 *  - CompanyAdmin: al crear una empresa.
 *  - Empleado: al asociarlo a una empresa (alta individual o masiva).
 *
 * <p>El CTA lleva al login con el email pre-cargado ({@code /login?email=...}).
 * Como el user se crea sin password, ese link cae directo en el flujo de
 * first-login (solo pide nombre + contraseña).
 */
@Component
@RequiredArgsConstructor
public class WelcomeEmails {

    private final EmailService emailService;
    private final EmailProperties props;

    /** Bienvenida al administrador de una empresa recién creada. */
    public void sendCompanyAdminWelcome(String toEmail, String companyName) {
        if (toEmail == null || toEmail.isBlank()) return;
        String empresa = companyName == null ? "tu empresa" : companyName;

        String intro = EmailLayout.paragraph("Diste de alta a <strong>" + EmailLayout.escape(empresa)
                + "</strong> en Arias y nos pone muy contentos sumar a tu equipo a nuestra mesa. "
                + "De ahora en más, tu gente va a poder pedir nuestra comida casera de bodegón directo en la oficina.")
            + ACTIVATE_PARAGRAPH;

        emailService.send(toEmail, "Bienvenido a Arias — Activá tu cuenta",
            buildHtml("¡Bienvenido a Arias!", intro, "Activar mi cuenta", loginUrl(toEmail)));
    }

    private static final String EMPLOYEE_SUBJECT = "Bienvenido a Arias — Activá tu cuenta";
    private static final String ACTIVATE_PARAGRAPH =
        EmailLayout.paragraph("Para empezar, activá tu cuenta creando tu contraseña.");
    private static final String EMPLOYEE_INTRO =
        EmailLayout.paragraph("Tu empresa te sumó a Arias, así que andá preparando la servilleta. "
            + "De ahora en más vas a poder elegir tu almuerzo entre nuestros platos caseros de bodegón, y te lo llevamos directo a la oficina.")
        + ACTIVATE_PARAGRAPH;

    /** Bienvenida a un empleado recién asociado a una empresa. */
    public void sendEmployeeWelcome(String toEmail) {
        if (toEmail == null || toEmail.isBlank()) return;

        emailService.send(toEmail, EMPLOYEE_SUBJECT,
            buildHtml("¡Bienvenido a Arias!", EMPLOYEE_INTRO, "Activar mi cuenta", loginUrl(toEmail)));
    }

    /**
     * Bienvenida a varios empleados en una sola request a Resend (batch).
     * Para el alta masiva: evita el rate limit de 2 req/s.
     */
    public void sendEmployeeWelcomeBatch(List<String> toEmails) {
        List<OutgoingEmail> batch = toEmails.stream()
            .filter(e -> e != null && !e.isBlank())
            .map(e -> new OutgoingEmail(e, EMPLOYEE_SUBJECT,
                buildHtml("¡Bienvenido a Arias!", EMPLOYEE_INTRO, "Activar mi cuenta", loginUrl(e))))
            .toList();
        emailService.sendBatch(batch);
    }

    /** Link al login con el email pre-cargado (cae en el flujo de first-login). */
    private String loginUrl(String email) {
        return props.appUrl() + "/login?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
    }

    private String buildHtml(String heading, String introHtml, String ctaLabel, String ctaUrl) {
        return EmailLayout.render(EmailContent.of(heading, introHtml).withCta(ctaLabel, ctaUrl));
    }
}
