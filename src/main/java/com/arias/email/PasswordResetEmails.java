package com.arias.email;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PasswordResetEmails {

    private final EmailService emailService;
    private final EmailProperties props;

    public void sendResetLink(String toEmail, String firstName, String tokenValue) {
        String safeName = (firstName != null && !firstName.isBlank()) ? firstName : "che";
        String resetUrl = props.appUrl() + "/reset-password?token=" + tokenValue;

        String html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f7f1ed; padding: 40px 20px; color: #2a1a14;">
              <table cellpadding="0" cellspacing="0" border="0" align="center" style="max-width: 480px; background: #fffdfb; border-radius: 8px; padding: 32px;">
                <tr><td>
                  <h1 style="font-family: Georgia, serif; color: #c5191d; font-size: 28px; margin: 0 0 8px;">ARIAS</h1>
                  <p style="color: #c5191d; text-transform: uppercase; letter-spacing: 2px; font-size: 11px; margin: 0 0 32px;">Bodegón · Parrilla</p>

                  <h2 style="font-family: Georgia, serif; font-size: 22px; margin: 0 0 16px;">¡Hola %s!</h2>
                  <p style="line-height: 1.6; margin: 0 0 16px;">Recibimos tu pedido para restablecer la contraseña. Hacé clic en el botón para crear una nueva.</p>
                  <p style="line-height: 1.6; margin: 0 0 24px;">El link expira en 15 minutos.</p>

                  <a href="%s" style="display: inline-block; background: #c5191d; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: 600; text-transform: uppercase; letter-spacing: 1px; font-size: 13px;">Restablecer contraseña</a>

                  <p style="color: #6b5b52; font-size: 11px; margin: 32px 0 0; text-transform: uppercase; letter-spacing: 1px;">Familia Mazzariello · Desde 2015</p>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(safeName, resetUrl);

        emailService.send(toEmail, "Restablecé tu contraseña — Arias", html);
    }
}
