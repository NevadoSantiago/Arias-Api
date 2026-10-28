package com.arias.orders.notifications;

import com.arias.email.EmailContent;
import com.arias.email.EmailLayout;
import com.arias.email.EmailProperties;
import com.arias.email.EmailService;
import com.arias.orders.Order;
import com.arias.orders.OrderItem;
import com.arias.users.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Mails del ciclo del pedido (unidad 12, diseño §Decisión 11): resumen
 * matutino, alerta de cancelación y recordatorio de retiro. Mismo patrón
 * visual que {@link com.arias.email.WelcomeEmails} y las demás plantillas de
 * {@code com.arias.email}.
 */
@Component
@RequiredArgsConstructor
public class OrderNotificationEmails {

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA =
        DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-AR"));

    private final EmailService emailService;
    private final EmailProperties props;

    /** Resumen matutino a los administradores — pedidos del día agrupados por horario de retiro. */
    public void sendDailySummary(List<User> admins, LocalDate fecha, List<Order> orders) {
        if (admins.isEmpty()) return;

        Map<LocalTime, List<Order>> byPickup = orders.stream()
            .collect(Collectors.groupingBy(
                o -> LocalTime.ofInstant(o.getPickupAt(), ZONE),
                TreeMap::new,
                Collectors.toList()));

        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<LocalTime, List<Order>> entry : byPickup.entrySet()) {
            List<Order> group = entry.getValue();
            int creditos = group.stream().mapToInt(Order::getCreditTotal).sum();
            rows.add(List.of("<strong>" + entry.getKey().format(HORA) + "</strong>",
                group.size() + " pedido(s)", creditos + " almuerzo(s)"));
        }

        String intro = EmailLayout.paragraph("Resumen de pedidos para hoy (%s) — %d pedido(s) en total."
                .formatted(fecha, orders.size()))
            + EmailLayout.summaryTable(List.of("Retiro", "Pedidos", "Almuerzos"), rows);

        String html = buildHtml("Resumen del día", intro);
        for (User admin : admins) {
            if (admin.getEmail() == null || admin.getEmail().isBlank()) continue;
            emailService.send(admin.getEmail(), "Resumen de pedidos de hoy — Arias", html);
        }
    }

    /** Alerta de cancelación — a los administradores y copia al cliente que canceló. */
    public void sendCancellationAlert(List<User> admins, OrderCancelledEvent event) {
        String hora = LocalTime.ofInstant(event.pickupAt(), ZONE).format(HORA);
        String nombre = EmailLayout.escape(event.userDisplayName());

        String introAdmin = EmailLayout.paragraph("El pedido #%d de <strong>%s</strong> se canceló. Horario de retiro original: <strong>%s</strong>."
            .formatted(event.orderId(), nombre, hora));
        String htmlAdmin = buildHtml("Pedido cancelado", introAdmin);
        for (User admin : admins) {
            if (admin.getEmail() == null || admin.getEmail().isBlank()) continue;
            emailService.send(admin.getEmail(), "Pedido #" + event.orderId() + " cancelado — Arias", htmlAdmin);
        }

        if (event.userEmail() != null && !event.userEmail().isBlank()) {
            String introCliente = EmailLayout.paragraph("Confirmamos que cancelaste tu pedido #%d, que tenías programado para retirar a las <strong>%s</strong>. %s"
                .formatted(event.orderId(), hora, event.refunded()
                    ? "Tus almuerzos ya vuelven a estar disponibles en tu saldo."
                    : "El pedido se canceló fuera de término, así que el almuerzo no se devuelve."));
            String htmlCliente = buildHtml("Cancelaste tu pedido", introCliente);
            emailService.send(event.userEmail(), "Cancelaste tu pedido #" + event.orderId() + " — Arias", htmlCliente);
        }
    }

    /**
     * Aviso al cliente (unidad B8): el corte canceló su pedido porque el pago
     * de Mercado Pago no se registró a tiempo. Si el pago se acredita después,
     * los almuerzos quedan disponibles en su cuenta.
     */
    public void sendUnpaidOrderCancelled(OrderUnpaidCancelledEvent event) {
        if (event.userEmail() == null || event.userEmail().isBlank()) return;

        var pickup = event.pickupAt().atZone(ZONE);
        String dia = pickup.format(DIA);
        String hora = pickup.format(HORA);
        String nombre = EmailLayout.escape(event.userDisplayName());

        String intro = EmailLayout.paragraph("Hola %s, cancelamos tu pedido #%d (retiro el %s a las <strong>%s</strong>) porque no registramos el pago de Mercado Pago antes del horario de corte."
                .formatted(nombre, event.orderId(), dia, hora))
            + EmailLayout.paragraph("Si el pago se acredita más tarde, no lo perdés: tus almuerzos disponibles quedan en tu cuenta para usarlos en otro pedido.");

        emailService.send(event.userEmail(), "Cancelamos tu pedido #" + event.orderId() + " por falta de pago — Arias",
            EmailLayout.render(EmailContent.of("Tu pedido se canceló", intro).withCta("Ver mis pedidos", props.appUrl())));
    }

    /** Recordatorio de retiro al cliente. */
    public void sendPickupReminder(Order order) {
        User user = order.getUser();
        if (user.getEmail() == null || user.getEmail().isBlank()) return;

        String hora = LocalTime.ofInstant(order.getPickupAt(), ZONE).format(HORA);
        List<String> platos = order.getItems().stream()
            .map(OrderNotificationEmails::itemLabel)
            .toList();

        String intro = EmailLayout.paragraph("Tu pedido #%d está casi listo — retirálo a las <strong>%s</strong>."
                .formatted(order.getId(), hora))
            + EmailLayout.paragraph("En el mostrador te vamos a llamar por <strong>%s</strong>."
                .formatted(EmailLayout.escape(user.displayName())))
            + EmailLayout.labelledBox("Tu pedido", EmailLayout.bulletList(platos));

        emailService.send(user.getEmail(), "Tu almuerzo te espera a las " + hora + " — Arias",
            buildHtml("¡Ya casi es la hora!", intro));
    }

    /** "Plato c/ Acompañamiento" (guarnición o salsa) como en la cocina; solo el plato si no lleva. */
    private static String itemLabel(OrderItem item) {
        String side = item.getSideNombre();
        return side == null || side.isBlank()
            ? EmailLayout.escape(item.getDishNombre())
            : EmailLayout.escape(item.getDishNombre() + " c/ " + side);
    }

    private static String buildHtml(String heading, String introHtml) {
        return EmailLayout.render(EmailContent.of(heading, introHtml));
    }
}
