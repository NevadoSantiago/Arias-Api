package com.arias.orders.notifications;

import com.arias.email.EmailService;
import com.arias.orders.Order;
import com.arias.orders.OrderItem;
import com.arias.users.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    private final EmailService emailService;

    /** Resumen matutino a los administradores — pedidos del día agrupados por horario de retiro. */
    public void sendDailySummary(List<User> admins, LocalDate fecha, List<Order> orders) {
        if (admins.isEmpty()) return;

        Map<LocalTime, List<Order>> byPickup = orders.stream()
            .collect(Collectors.groupingBy(
                o -> LocalTime.ofInstant(o.getPickupAt(), ZONE),
                TreeMap::new,
                Collectors.toList()));

        StringBuilder rows = new StringBuilder();
        for (Map.Entry<LocalTime, List<Order>> entry : byPickup.entrySet()) {
            List<Order> group = entry.getValue();
            int creditos = group.stream().mapToInt(Order::getCreditTotal).sum();
            rows.append("""
                <tr>
                  <td style="padding: 8px 0; border-bottom: 1px solid #e8ddd5;"><strong>%s</strong></td>
                  <td style="padding: 8px 0; border-bottom: 1px solid #e8ddd5;">%d pedido(s)</td>
                  <td style="padding: 8px 0; border-bottom: 1px solid #e8ddd5;">%d almuerzo(s)</td>
                </tr>
                """.formatted(entry.getKey().format(HORA), group.size(), creditos));
        }

        String intro = """
            <p style="line-height: 1.6; margin: 0 0 16px;">Resumen de pedidos para hoy (%s) — %d pedido(s) en total.</p>
            <table cellpadding="0" cellspacing="0" width="100%%" style="margin: 0 0 24px;">
              <tr>
                <th style="text-align: left; padding: 8px 0; border-bottom: 2px solid #c5191d;">Retiro</th>
                <th style="text-align: left; padding: 8px 0; border-bottom: 2px solid #c5191d;">Pedidos</th>
                <th style="text-align: left; padding: 8px 0; border-bottom: 2px solid #c5191d;">Almuerzos</th>
              </tr>
              %s
            </table>
            """.formatted(fecha, orders.size(), rows);

        String html = buildHtml("Resumen del día", intro);
        for (User admin : admins) {
            if (admin.getEmail() == null || admin.getEmail().isBlank()) continue;
            emailService.send(admin.getEmail(), "Resumen de pedidos de hoy — Arias", html);
        }
    }

    /** Alerta de cancelación — a los administradores y copia al cliente que canceló. */
    public void sendCancellationAlert(List<User> admins, OrderCancelledEvent event) {
        String hora = LocalTime.ofInstant(event.pickupAt(), ZONE).format(HORA);
        String nombre = HtmlUtils.htmlEscape(event.userDisplayName());

        String introAdmin = """
            <p style="line-height: 1.6; margin: 0 0 16px;">El pedido #%d de <strong>%s</strong> se canceló. Horario de retiro original: <strong>%s</strong>.</p>
            """.formatted(event.orderId(), nombre, hora);
        String htmlAdmin = buildHtml("Pedido cancelado", introAdmin);
        for (User admin : admins) {
            if (admin.getEmail() == null || admin.getEmail().isBlank()) continue;
            emailService.send(admin.getEmail(), "Pedido #" + event.orderId() + " cancelado — Arias", htmlAdmin);
        }

        if (event.userEmail() != null && !event.userEmail().isBlank()) {
            String introCliente = """
                <p style="line-height: 1.6; margin: 0 0 16px;">Confirmamos que cancelaste tu pedido #%d, que tenías programado para retirar a las <strong>%s</strong>. Tus almuerzos ya vuelven a estar disponibles en tu saldo.</p>
                """.formatted(event.orderId(), hora);
            String htmlCliente = buildHtml("Cancelaste tu pedido", introCliente);
            emailService.send(event.userEmail(), "Cancelaste tu pedido #" + event.orderId() + " — Arias", htmlCliente);
        }
    }

    /** Recordatorio de retiro al cliente. */
    public void sendPickupReminder(Order order) {
        User user = order.getUser();
        if (user.getEmail() == null || user.getEmail().isBlank()) return;

        String hora = LocalTime.ofInstant(order.getPickupAt(), ZONE).format(HORA);
        String platos = order.getItems().stream()
            .map(OrderItem::getDishNombre)
            .collect(Collectors.joining(", "));

        String intro = """
            <p style="line-height: 1.6; margin: 0 0 16px;">Tu pedido #%d está casi listo — retirálo a las <strong>%s</strong>.</p>
            <p style="line-height: 1.6; margin: 0 0 24px;">%s</p>
            """.formatted(order.getId(), hora, HtmlUtils.htmlEscape(platos));

        emailService.send(user.getEmail(), "Tu almuerzo te espera a las " + hora + " — Arias",
            buildHtml("¡Ya casi es la hora!", intro));
    }

    private static String buildHtml(String heading, String introHtml) {
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

                  <p style="color: #6b5b52; font-size: 11px; margin: 32px 0 0; text-transform: uppercase; letter-spacing: 1px;">Familia Mazzariello · Desde 2015</p>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(heading, introHtml);
    }
}
