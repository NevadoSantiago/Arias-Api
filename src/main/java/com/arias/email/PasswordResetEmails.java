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

        String html = EmailLayout.render(EmailContent.of("¡Hola " + safeName + "!",
                EmailLayout.paragraph("Recibimos tu pedido para restablecer la contraseña. Hacé clic en el botón para crear una nueva.")
                    + EmailLayout.paragraph("El link expira en 15 minutos."))
            .withCta("Restablecer contraseña", resetUrl));

        emailService.send(toEmail, "Restablecé tu contraseña — Arias", html);
    }
}
