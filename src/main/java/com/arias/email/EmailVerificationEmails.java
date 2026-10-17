package com.arias.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mail de verificación de correo del autorregistro público (spec
 * {@code self-registration}) — mismo patrón visual que {@link PasswordResetEmails}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationEmails {

    private final EmailService emailService;
    private final EmailProperties props;

    public void sendVerificationLink(String toEmail, String firstName, String tokenValue) {
        String safeName = (firstName != null && !firstName.isBlank()) ? firstName : "che";
        String verifyUrl = props.appUrl() + "/verify-email?token=" + tokenValue;

        String html = EmailLayout.render(EmailContent.of("¡Hola " + safeName + "!",
                EmailLayout.paragraph("Gracias por registrarte en Arias. Confirmá tu correo para activar tu cuenta y recibir tu almuerzo de bienvenida.")
                    + EmailLayout.paragraph("El link expira en 30 minutos."))
            .withCta("Verificar mi correo", verifyUrl));

        // Sin Resend configurado el mail no sale y el token se guarda hasheado,
        // así que no habría forma de verificar en local: logueamos el link.
        // Nunca ocurre en producción, donde la API key siempre está seteada.
        if (!props.isConfigured()) {
            log.warn("[DEV] Resend no configurado — link de verificación para {}: {}", toEmail, verifyUrl);
        }

        emailService.send(toEmail, "Confirmá tu correo — Arias", html);
    }
}
