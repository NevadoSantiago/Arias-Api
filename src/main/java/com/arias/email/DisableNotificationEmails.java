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
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("d/M");

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

        String html = EmailLayout.render(EmailContent.of("¡Hola " + firstName + "!",
                EmailLayout.paragraph("Te queríamos avisar que <strong>" + EmailLayout.escape(itemName) + "</strong>, "
                    + "que elegiste para hoy, ya no está disponible.")
                    + EmailLayout.paragraph("Tu pedido sigue marcado, pero si querés cambiarlo "
                        + "podés entrar a la app antes de las <strong>" + HM.format(cutoffTime) + "</strong>."))
            .withCta("Modificar mi pedido", props.appUrl()));

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
    @SuppressWarnings("unused") // Reason queda para futuros usos / logging
    public void notifyOrderCancelled(DailyChoice order, Reason reason, LocalTime cutoffTime) {
        sendCancellationMail(order);
    }

    /**
     * Mail al empleado cuando el admin remueve un plato especial de una fecha
     * (vía el calendario) y el empleado tenía ese plato programado.
     */
    public void notifySpecialRemovedForDate(DailyChoice order) {
        sendCancellationMail(order);
    }

    /**
     * Texto único para cualquier cancelación de pedido. El motivo (plato deshabilitado,
     * especial removido del calendario, etc.) NO se menciona — el mail es breve y amable.
     */
    private void sendCancellationMail(DailyChoice order) {
        String firstName = safeName(order.getUser().getFirstName());
        String fechaStr = DM.format(order.getFecha());

        String html = EmailLayout.render(EmailContent.of("Hola " + firstName,
                EmailLayout.paragraph("Lamentablemente tenemos que pedirte que cambies tu plato programado para el <strong>"
                    + EmailLayout.escape(fechaStr) + "</strong>.")
                    + EmailLayout.paragraph("Ya están disponibles en la app las opciones de cambio.")
                    + EmailLayout.paragraph("Con tu permiso, muchas gracias!"))
            .withCta("Programar pedido", props.appUrl()));

        emailService.send(
            order.getUser().getEmail(),
            "Cambio en tu almuerzo del " + fechaStr,
            html
        );
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static String safeName(String name) {
        return (name != null && !name.isBlank()) ? name : "che";
    }
}
