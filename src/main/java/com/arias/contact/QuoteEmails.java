package com.arias.contact;

import com.arias.email.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Arma y envía el mail de "nueva cotización" al equipo de Arias.
 * Calca el estilo de marca de {@code PasswordResetEmails}.
 */
@Component
public class QuoteEmails {

    private final EmailService emailService;
    private final String quoteTo;

    public QuoteEmails(
        EmailService emailService,
        @Value("${arias.contact.quote-to:}") String quoteTo
    ) {
        this.emailService = emailService;
        this.quoteTo = quoteTo;
    }

    public void sendQuoteRequest(QuoteRequest req) {
        // Escapamos TODO input del usuario: este HTML se renderiza en un cliente
        // de mail, así que evitamos inyección de markup/script.
        String nombre = HtmlUtils.htmlEscape(req.nombre());
        String empresa = HtmlUtils.htmlEscape(req.empresa());
        String email = HtmlUtils.htmlEscape(req.email());
        String telefono = blankToDash(req.telefono());
        String empleados = req.empleados() == null ? "—" : String.valueOf(req.empleados());
        String mensaje = blankToDash(req.mensaje());

        String html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f7f1ed; padding: 40px 20px; color: #2a1a14;">
              <table cellpadding="0" cellspacing="0" border="0" align="center" style="max-width: 520px; background: #fffdfb; border-radius: 8px; padding: 32px;">
                <tr><td>
                  <h1 style="font-family: Georgia, serif; color: #c5191d; font-size: 28px; margin: 0 0 8px;">ARIAS</h1>
                  <p style="color: #c5191d; text-transform: uppercase; letter-spacing: 2px; font-size: 11px; margin: 0 0 28px;">Bodegón · Parrilla</p>

                  <h2 style="font-family: Georgia, serif; font-size: 22px; margin: 0 0 20px;">Nueva solicitud de cotización</h2>

                  <table cellpadding="0" cellspacing="0" border="0" width="100%%" style="font-size: 14px; line-height: 1.6;">
                    <tr><td style="padding: 6px 0; color: #6b5b52; width: 130px;">Nombre</td><td style="padding: 6px 0; font-weight: 600;">%s</td></tr>
                    <tr><td style="padding: 6px 0; color: #6b5b52;">Empresa</td><td style="padding: 6px 0; font-weight: 600;">%s</td></tr>
                    <tr><td style="padding: 6px 0; color: #6b5b52;">Email</td><td style="padding: 6px 0;"><a href="mailto:%s" style="color: #c5191d;">%s</a></td></tr>
                    <tr><td style="padding: 6px 0; color: #6b5b52;">Teléfono</td><td style="padding: 6px 0;">%s</td></tr>
                    <tr><td style="padding: 6px 0; color: #6b5b52;">Empleados</td><td style="padding: 6px 0;">%s</td></tr>
                  </table>

                  <p style="color: #6b5b52; font-size: 13px; margin: 20px 0 4px;">Mensaje</p>
                  <p style="line-height: 1.6; margin: 0; padding: 12px 16px; background: #f7f1ed; border-radius: 6px; white-space: pre-wrap;">%s</p>

                  <p style="color: #6b5b52; font-size: 11px; margin: 28px 0 0; text-transform: uppercase; letter-spacing: 1px;">Enviado desde la web de Arias</p>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(nombre, empresa, email, email, telefono, empleados, mensaje);

        emailService.send(quoteTo, "Nueva cotización — " + empresa, html);
    }

    private static String blankToDash(String v) {
        return (v == null || v.isBlank()) ? "—" : HtmlUtils.htmlEscape(v);
    }
}
