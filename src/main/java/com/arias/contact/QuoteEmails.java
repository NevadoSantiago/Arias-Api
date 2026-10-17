package com.arias.contact;

import com.arias.email.EmailContent;
import com.arias.email.EmailLayout;
import com.arias.email.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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
        String nombre = EmailLayout.escape(req.nombre());
        String empresa = EmailLayout.escape(req.empresa());
        String email = EmailLayout.escape(req.email());
        String telefono = blankToDash(req.telefono());
        String empleados = req.empleados() == null ? "—" : String.valueOf(req.empleados());
        String mensaje = blankToDash(req.mensaje());

        String body = EmailLayout.labelValueTable(List.of(
                Map.entry("Nombre", "<strong>" + nombre + "</strong>"),
                Map.entry("Empresa", "<strong>" + empresa + "</strong>"),
                Map.entry("Email", "<a href=\"mailto:" + email + "\" style=\"color:#B3252B;\">" + email + "</a>"),
                Map.entry("Teléfono", telefono),
                Map.entry("Empleados", empleados)))
            + EmailLayout.labelledBox("Mensaje", "<div style=\"white-space:pre-wrap;\">" + mensaje + "</div>");

        String html = EmailLayout.render(EmailContent.of("Nueva solicitud de cotización", body)
            .withFooter("Enviado desde la web de Arias"));

        emailService.send(quoteTo, "Nueva cotización — " + empresa, html);
    }

    private static String blankToDash(String v) {
        return (v == null || v.isBlank()) ? "—" : EmailLayout.escape(v);
    }
}
