package com.arias.email;

import com.arias.contact.QuoteEmails;
import com.arias.contact.QuoteRequest;
import com.arias.orders.Order;
import com.arias.orders.OrderItem;
import com.arias.orders.notifications.OrderCancelledEvent;
import com.arias.orders.notifications.OrderNotificationEmails;
import com.arias.orders.notifications.OrderUnpaidCancelledEvent;
import com.arias.payments.CreditPurchaseCreditedEvent;
import com.arias.payments.CreditPurchaseReversedEvent;
import com.arias.payments.PaymentEmails;
import com.arias.users.User;
import com.arias.users.UserRepository;
import com.arias.users.notifications.OrderReminderEmail;
import com.arias.users.notifications.ReminderUnsubscribeToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mails de pedidos, pagos, cotización y recordatorio: todos salen por {@link EmailLayout}. */
class OrderPaymentQuoteReminderEmailsTest {

    private static final String BAND = "Bodegón · Parrilla";
    private static final String SCHEME = "<meta name=\"color-scheme\" content=\"light\">";

    private EmailService emailService;
    private EmailProperties props;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        props = new EmailProperties("key", "a@b.test", "Arias", "https://app.test");
    }

    private String html(String to, String subject) {
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(eq(to), eq(subject), html.capture());
        assertThat(html.getValue()).contains(SCHEME).contains(BAND);
        return html.getValue();
    }

    private static User user(String email, String nickname, String firstName) {
        User u = mock(User.class);
        when(u.getEmail()).thenReturn(email);
        when(u.getFirstName()).thenReturn(firstName);
        when(u.displayName()).thenReturn(nickname);
        return u;
    }

    private static Order order(User user, String... dishes) {
        Order o = mock(Order.class);
        when(o.getId()).thenReturn(42L);
        when(o.getUser()).thenReturn(user);
        when(o.getPickupAt()).thenReturn(Instant.parse("2026-03-10T15:00:00Z")); // 12:00 ART
        List<OrderItem> items = new java.util.ArrayList<>();
        for (String d : dishes) {
            // "Plato|Acompañamiento" sets a side (guarnición or salsa); no bar means no side.
            String[] parts = d.split("\\|", 2);
            OrderItem it = mock(OrderItem.class);
            when(it.getDishNombre()).thenReturn(parts[0]);
            when(it.getSideNombre()).thenReturn(parts.length > 1 ? parts[1] : null);
            items.add(it);
        }
        when(o.getItems()).thenReturn(items);
        return o;
    }

    // ─── Pickup reminder ────────────────────────────────────────────────

    @Test
    @DisplayName("recordatorio de retiro: nombre de mostrador escapado y un <li> por plato, con su acompañamiento")
    void pickupReminder() {
        User u = user("c@x.test", "Ana <b>", "Ana");
        new OrderNotificationEmails(emailService, props)
            .sendPickupReminder(order(u, "Milanesa|Puré <b>mixto</b>", "Flan <i>mixto</i>", "Ravioles|Salsa fileto"));

        String html = html("c@x.test", "Tu almuerzo te espera a las 12:00 — Arias");
        assertThat(html)
            .contains(">¡Ya casi es la hora!</h2>")
            .contains("Tu pedido #42 está casi listo — retirálo a las <strong>12:00</strong>.")
            .contains("En el mostrador te vamos a llamar por <strong>Ana &lt;b&gt;</strong>.")
            .contains("Tu pedido</div>")
            .contains(">Milanesa c/ Puré &lt;b&gt;mixto&lt;/b&gt;</li>")
            .contains(">Flan &lt;i&gt;mixto&lt;/i&gt;</li>")
            .contains(">Ravioles c/ Salsa fileto</li>")
            .doesNotContain("Milanesa, Flan")
            .doesNotContain("<i>mixto</i>");
        assertThat(html.split("<li", -1)).hasSize(4);
    }

    // ─── Daily summary and cancellations ────────────────────────────────

    @Test
    @DisplayName("resumen diario: tabla con encabezado y una fila por horario")
    void dailySummary() {
        User admin = user("adm@x.test", "Adm", "Adm");
        Order o1 = order(admin, "A");
        when(o1.getCreditTotal()).thenReturn(2);
        new OrderNotificationEmails(emailService, props)
            .sendDailySummary(List.of(admin), LocalDate.of(2026, 3, 10), List.of(o1));

        String html = html("adm@x.test", "Resumen de pedidos de hoy — Arias");
        assertThat(html)
            .contains(">Resumen del día</h2>")
            .contains("Resumen de pedidos para hoy (2026-03-10) — 1 pedido(s) en total.")
            .contains("border-bottom:2px solid #DC2C2E")
            .contains(">Retiro</th>").contains(">Pedidos</th>").contains(">Almuerzos</th>")
            .contains("<strong>12:00</strong>")
            .contains("1 pedido(s)").contains("2 almuerzo(s)");
    }

    @Test
    @DisplayName("cancelación: aviso a admin y al cliente, nombre escapado")
    void cancellationAlert() {
        User admin = user("adm@x.test", "Adm", "Adm");
        OrderCancelledEvent ev = new OrderCancelledEvent(42L, 7L, "c@x.test", "Cli <b>",
            Instant.parse("2026-03-10T15:00:00Z"), 1, true);
        new OrderNotificationEmails(emailService, props).sendCancellationAlert(List.of(admin), ev);

        assertThat(html("adm@x.test", "Pedido #42 cancelado — Arias"))
            .contains(">Pedido cancelado</h2>")
            .contains("El pedido #42 de <strong>Cli &lt;b&gt;</strong> se canceló. Horario de retiro original: <strong>12:00</strong>.");
        assertThat(html("c@x.test", "Cancelaste tu pedido #42 — Arias"))
            .contains(">Cancelaste tu pedido</h2>")
            .contains("Confirmamos que cancelaste tu pedido #42")
            .contains("Tus almuerzos ya vuelven a estar disponibles en tu saldo.");
    }

    @Test
    @DisplayName("cancelación fuera de término: el mail al cliente avisa que el almuerzo no se devuelve")
    void lateCancellationAlert() {
        User admin = user("adm@x.test", "Adm", "Adm");
        OrderCancelledEvent ev = new OrderCancelledEvent(42L, 7L, "c@x.test", "Cli",
            Instant.parse("2026-03-10T15:00:00Z"), 1, false);
        new OrderNotificationEmails(emailService, props).sendCancellationAlert(List.of(admin), ev);

        assertThat(html("c@x.test", "Cancelaste tu pedido #42 — Arias"))
            .contains("Confirmamos que cancelaste tu pedido #42")
            .contains("El pedido se canceló fuera de término, así que el almuerzo no se devuelve.")
            .doesNotContain("vuelven a estar disponibles");
    }

    @Test
    @DisplayName("cancelación por falta de pago: copy y botón 'Ver mis pedidos'")
    void unpaidCancelled() {
        new OrderNotificationEmails(emailService, props).sendUnpaidOrderCancelled(
            new OrderUnpaidCancelledEvent(42L, 7L, "c@x.test", "Cli", Instant.parse("2026-03-10T15:00:00Z")));
        assertThat(html("c@x.test", "Cancelamos tu pedido #42 por falta de pago — Arias"))
            .contains(">Tu pedido se canceló</h2>")
            .contains("Hola Cli, cancelamos tu pedido #42 (retiro el martes 10 de marzo a las <strong>12:00</strong>)")
            .contains("Si el pago se acredita más tarde")
            .contains("href=\"https://app.test\"")
            .contains(">Ver mis pedidos</a>");
    }

    // ─── Payments ───────────────────────────────────────────────────────

    @Test
    @DisplayName("pagos: acreditación y reembolso")
    void payments() {
        UserRepository repo = mock(UserRepository.class);
        User admin = user("adm@x.test", "Adm", "Adm");
        when(repo.findActiveSuperAdmins()).thenReturn(List.of(admin));
        PaymentEmails emails = new PaymentEmails(emailService, repo);
        UUID id = UUID.randomUUID();

        emails.onCredited(new CreditPurchaseCreditedEvent(id, 7L, "c@x.test", null, 5));
        assertThat(html("c@x.test", "Acreditamos tu compra — Arias"))
            .contains(">¡Listo!</h2>")
            .contains("Te acreditamos <strong>5 almuerzo(s)</strong> en tu saldo — ¡ya podés pedir!");

        emails.onReversed(new CreditPurchaseReversedEvent(id, 7L, "c@x.test", 2, 2, 5, false));
        assertThat(html("adm@x.test", "Reembolso detectado — Arias"))
            .contains(">Reembolso de Mercado Pago</h2>")
            .contains("revertida parcialmente")
            .contains("se revirtieron 2 almuerzo(s) de 5, acumulando 2 ya revertidos en total.");
    }

    // ─── Quote ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("cotización: tabla etiqueta/valor, mensaje en caja y pie propio")
    void quote() {
        new QuoteEmails(emailService, "team@arias.test").sendQuoteRequest(
            new QuoteRequest("Ana", "Foo <b>", "ana@x.test", null, 10, "Hola\nmundo"));
        String html = html("team@arias.test", "Nueva cotización — Foo &lt;b&gt;");
        assertThat(html)
            .contains(">Nueva solicitud de cotización</h2>")
            .contains("Nombre").contains("Ana")
            .contains("Foo &lt;b&gt;")
            .contains("href=\"mailto:ana@x.test\"")
            .contains("Teléfono").contains("—")
            .contains("Empleados").contains(">10<")
            .contains("Mensaje").contains("Hola\nmundo").contains("white-space:pre-wrap")
            .contains("Enviado desde la web de Arias")
            .doesNotContain("Familia Mazzariello");
    }

    // ─── Daily reminder ─────────────────────────────────────────────────

    @Test
    @DisplayName("recordatorio diario: botón 'Ver menú' y link de baja bajo el pie")
    void dailyReminder() {
        ReminderUnsubscribeToken tokens = mock(ReminderUnsubscribeToken.class);
        when(tokens.generate(7L)).thenReturn("tok");
        User u = user("e@x.test", "E", "Eva");
        when(u.getId()).thenReturn(7L);
        new OrderReminderEmail(emailService, props, tokens).send(u);

        String html = html("e@x.test", "¿Te olvidaste tu almuerzo de hoy?");
        assertThat(html)
            .contains(">Hola Eva</h2>")
            .contains("Vimos que todavía no elegiste tu almuerzo de hoy. ¡Hay opciones esperándote!")
            .contains("Entrá a la app y armá tu pedido antes del cierre.")
            .contains("href=\"https://app.test/orders/today\"")
            .contains(">Ver menú</a>")
            .contains("href=\"https://app.test/unsubscribe-reminder?t=tok\"")
            .contains("No quiero recibir más estos avisos");
        assertThat(html.indexOf("No quiero recibir")).isGreaterThan(html.indexOf("Familia Mazzariello"));
    }
}
