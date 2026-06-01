package com.arias.users.notifications;

import com.arias.email.EmailProperties;
import com.arias.email.EmailService;
import com.arias.users.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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

    public void send(User user) {
        String firstName = safeName(user.getFirstName());
        String token = tokens.generate(user.getId());
        String unsubscribeUrl = props.appUrl() + "/unsubscribe-reminder?t=" + token;
        String menuUrl = props.appUrl() + "/orders/today";

        String html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f7f1ed; padding: 40px 20px; color: #2a1a14;">
              <table cellpadding="0" cellspacing="0" border="0" align="center" style="max-width: 480px; background: #fffdfb; border-radius: 8px; padding: 32px;">
                <tr><td>
                  <h1 style="font-family: Georgia, serif; color: #c5191d; font-size: 28px; margin: 0 0 8px;">ARIAS</h1>
                  <p style="color: #c5191d; text-transform: uppercase; letter-spacing: 2px; font-size: 11px; margin: 0 0 32px;">Bodegón · Parrilla</p>

                  <h2 style="font-family: Georgia, serif; font-size: 22px; margin: 0 0 16px;">Hola %s</h2>
                  <p style="line-height: 1.6; margin: 0 0 16px;">Vimos que todavía no elegiste tu almuerzo de hoy. ¡Hay opciones esperándote!</p>
                  <p style="line-height: 1.6; margin: 0 0 24px;">Entrá a la app y armá tu pedido antes del cierre.</p>

                  <a href="%s" style="display: inline-block; background: #c5191d; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: 600; text-transform: uppercase; letter-spacing: 1px; font-size: 13px;">Ver menú</a>

                  <p style="color: #6b5b52; font-size: 11px; margin: 32px 0 0; text-transform: uppercase; letter-spacing: 1px;">Familia Mazzariello · Desde 2015</p>

                  <p style="color: #9a8a7e; font-size: 11px; margin: 24px 0 0; line-height: 1.5;">
                    <a href="%s" style="color: #9a8a7e; text-decoration: underline;">No quiero recibir más estos avisos</a>
                  </p>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(firstName, menuUrl, unsubscribeUrl);

        emailService.send(user.getEmail(), "¿Te olvidaste tu almuerzo de hoy?", html);
    }

    private static String safeName(String name) {
        return (name != null && !name.isBlank()) ? name : "che";
    }
}
