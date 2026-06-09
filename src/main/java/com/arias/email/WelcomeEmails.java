package com.arias.email;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
        String empresa = HtmlUtils.htmlEscape(companyName == null ? "tu empresa" : companyName);

        String intro = """
            <p style="line-height: 1.6; margin: 0 0 16px;">Diste de alta a <strong>%s</strong> en Arias y nos pone muy contentos sumar a tu equipo a nuestra mesa. \
            De ahora en más, tu gente va a poder pedir nuestra comida casera de bodegón directo en la oficina.</p>
            <p style="line-height: 1.6; margin: 0 0 24px;">Para empezar, activá tu cuenta creando tu contraseña.</p>
            """.formatted(empresa);

        emailService.send(toEmail, "Bienvenido a Arias — Activá tu cuenta",
            buildHtml("¡Bienvenido a Arias!", intro, "Activar mi cuenta", loginUrl(toEmail)));
    }

    /** Bienvenida a un empleado recién asociado a una empresa. */
    public void sendEmployeeWelcome(String toEmail) {
        if (toEmail == null || toEmail.isBlank()) return;

        String intro = """
            <p style="line-height: 1.6; margin: 0 0 16px;">Tu empresa te sumó a Arias, así que andá preparando la servilleta. \
            De ahora en más vas a poder elegir tu almuerzo entre nuestros platos caseros de bodegón, y te lo llevamos directo a la oficina.</p>
            <p style="line-height: 1.6; margin: 0 0 24px;">Para empezar, activá tu cuenta creando tu contraseña.</p>
            """;

        emailService.send(toEmail, "Bienvenido a Arias — Activá tu cuenta",
            buildHtml("¡Bienvenido a Arias!", intro, "Activar mi cuenta", loginUrl(toEmail)));
    }

    /** Link al login con el email pre-cargado (cae en el flujo de first-login). */
    private String loginUrl(String email) {
        return props.appUrl() + "/login?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
    }

    private String buildHtml(String heading, String introHtml, String ctaLabel, String ctaUrl) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f7f1ed; padding: 40px 20px; color: #2a1a14;">
              <table cellpadding="0" cellspacing="0" border="0" align="center" style="max-width: 480px; background: #fffdfb; border-radius: 8px; padding: 32px;">
                <tr><td>
                  <h1 style="font-family: Georgia, serif; color: #c5191d; font-size: 28px; margin: 0 0 8px;">ARIAS</h1>
                  <p style="color: #c5191d; text-transform: uppercase; letter-spacing: 2px; font-size: 11px; margin: 0 0 32px;">Bodegón · Parrilla</p>

                  <h2 style="font-family: Georgia, serif; font-size: 22px; margin: 0 0 16px;">%s</h2>
                  %s
                  <a href="%s" style="display: inline-block; background: #c5191d; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: 600; text-transform: uppercase; letter-spacing: 1px; font-size: 13px;">%s</a>

                  <p style="color: #6b5b52; font-size: 11px; margin: 32px 0 0; text-transform: uppercase; letter-spacing: 1px;">Familia Mazzariello · Desde 2015</p>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(heading, introHtml, ctaUrl, ctaLabel);
    }
}
