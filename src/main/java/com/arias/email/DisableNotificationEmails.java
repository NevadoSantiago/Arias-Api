package com.arias.email;

import com.arias.orders.DailyChoice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Templates HTML para los emails de notificación cuando se desactiva
 * un plato o side que ya tenía pedidos pendientes.
 *
 * <p>HTML inline simple — sin templating engine. Los emails son cortos y
 * con un solo CTA, no vale la pena montar Thymeleaf.
 */
@Component
@RequiredArgsConstructor
public class DisableNotificationEmails {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final EmailService emailService;
    private final EmailProperties props;

    public enum Reason {
        DISH_DISABLED("El plato que elegiste"),
        SIDE_DISABLED("El acompañamiento que elegiste");

        final String subjectFragment;
        Reason(String subjectFragment) { this.subjectFragment = subjectFragment; }
    }

    /**
     * Mail al empleado cuando el resto desactiva el plato/side pero MANTIENE
     * su pedido. El empleado puede editar antes del corte si quiere otra cosa.
     */
    public void notifyOrderKept(DailyChoice order, Reason reason, LocalTime cutoffTime) {
        String firstName = safeName(order.getUser().getFirstName());
        String itemName = reason == Reason.SIDE_DISABLED
            ? order.getSideNombre()
            : order.getDishNombre();

        String html = baseTemplate(
            "¡Hola " + firstName + "!",
            "Te queríamos avisar que <strong>" + itemName + "</strong>, " +
                "que elegiste para hoy, ya no está disponible.",
            "Tu pedido sigue marcado, pero si querés cambiarlo " +
                "podés entrar a la app antes de las <strong>" +
                HM.format(cutoffTime) + "</strong>.",
            "Modificar mi pedido"
        );

        emailService.send(
            order.getUser().getEmail(),
            reason.subjectFragment + " hoy ya no está disponible",
            html
        );
    }

    /**
     * Mail al empleado cuando el resto desactiva Y cancela el pedido.
     * El empleado tiene que hacer uno nuevo.
     */
    public void notifyOrderCancelled(DailyChoice order, Reason reason, LocalTime cutoffTime) {
        String firstName = safeName(order.getUser().getFirstName());
        String itemName = reason == Reason.SIDE_DISABLED
            ? order.getSideNombre()
            : order.getDishNombre();

        String html = baseTemplate(
            "¡Hola " + firstName + "!",
            "Tu pedido para hoy fue cancelado porque <strong>" + itemName + "</strong> " +
                "ya no está disponible.",
            "Podés hacer un nuevo pedido entrando a la app antes de las <strong>" +
                HM.format(cutoffTime) + "</strong>.",
            "Hacer un nuevo pedido"
        );

        emailService.send(
            order.getUser().getEmail(),
            "Tu pedido de hoy fue cancelado",
            html
        );
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private String baseTemplate(String heading, String body1, String body2, String ctaText) {
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
                  <p style="line-height: 1.6; margin: 0 0 16px;">%s</p>
                  <p style="line-height: 1.6; margin: 0 0 24px;">%s</p>

                  <a href="%s" style="display: inline-block; background: #c5191d; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: 600; text-transform: uppercase; letter-spacing: 1px; font-size: 13px;">%s</a>

                  <p style="color: #6b5b52; font-size: 11px; margin: 32px 0 0; text-transform: uppercase; letter-spacing: 1px;">Familia Mazzariello · Desde 2015</p>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(heading, body1, body2, props.appUrl(), ctaText);
    }

    private static String safeName(String name) {
        return (name != null && !name.isBlank()) ? name : "che";
    }
}
