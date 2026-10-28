package com.arias.email;

import com.arias.credits.CreditsProperties;
import com.arias.orders.DailyChoice;
import com.arias.users.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cada builder de {@code com.arias.email} sale por {@link EmailLayout} y conserva su copy. */
class AuthAndAvailabilityEmailsTest {

    private static final String BAND = "Bodegón · Parrilla";

    private EmailService emailService;
    private EmailProperties props;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        props = new EmailProperties("key", "a@b.test", "Arias", "https://app.test");
    }

    private String sentHtml(String to, String subject) {
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(eq(to), eq(subject), html.capture());
        assertThat(html.getValue()).contains("<meta name=\"color-scheme\" content=\"light\">");
        return html.getValue();
    }

    @Test
    @DisplayName("verificación: layout, saludo, copy y botón")
    void verification() {
        new EmailVerificationEmails(emailService, props, new CreditsProperties(true)).sendVerificationLink("a@x.test", "Ana", "tok");
        String html = sentHtml("a@x.test", "Confirmá tu correo — Arias");
        assertThat(html).contains(BAND).contains("color-scheme")
            .contains(">¡Hola Ana!</h2>")
            .contains("Gracias por registrarte en Arias. Confirmá tu correo para activar tu cuenta y recibir tu almuerzo de bienvenida.")
            .contains("El link expira en 30 minutos.")
            .contains("href=\"https://app.test/verify-email?token=tok\"")
            .contains(">Verificar mi correo</a>")
            .contains("Familia Mazzariello · Desde 2015");
    }

    @Test
    @DisplayName("verificación: con el regalo apagado no promete el almuerzo de bienvenida")
    void verificationWithoutWelcomeGrant() {
        new EmailVerificationEmails(emailService, props, new CreditsProperties(false)).sendVerificationLink("a@x.test", "Ana", "tok");
        String html = sentHtml("a@x.test", "Confirmá tu correo — Arias");
        assertThat(html)
            .contains("Gracias por registrarte en Arias. Confirmá tu correo para activar tu cuenta.")
            .doesNotContain("almuerzo de bienvenida");
    }

    @Test
    @DisplayName("verificación: nombre vacío cae en 'che' y se escapa")
    void verificationFallbackName() {
        new EmailVerificationEmails(emailService, props, new CreditsProperties(true)).sendVerificationLink("a@x.test", " ", "t");
        assertThat(sentHtml("a@x.test", "Confirmá tu correo — Arias")).contains("¡Hola che!");
    }

    @Test
    @DisplayName("reset de contraseña: layout, copy y botón")
    void passwordReset() {
        new PasswordResetEmails(emailService, props).sendResetLink("a@x.test", "Ana", "tok");
        String html = sentHtml("a@x.test", "Restablecé tu contraseña — Arias");
        assertThat(html).contains(BAND)
            .contains(">¡Hola Ana!</h2>")
            .contains("Recibimos tu pedido para restablecer la contraseña. Hacé clic en el botón para crear una nueva.")
            .contains("El link expira en 15 minutos.")
            .contains("href=\"https://app.test/reset-password?token=tok\"")
            .contains(">Restablecer contraseña</a>");
    }

    @Test
    @DisplayName("bienvenida de admin de empresa: layout, empresa escapada y botón")
    void welcomeCompanyAdmin() {
        new WelcomeEmails(emailService, props).sendCompanyAdminWelcome("adm@x.test", "Foo <b>");
        String html = sentHtml("adm@x.test", "Bienvenido a Arias — Activá tu cuenta");
        assertThat(html).contains(BAND)
            .contains(">¡Bienvenido a Arias!</h2>")
            .contains("Diste de alta a <strong>Foo &lt;b&gt;</strong> en Arias")
            .contains("Para empezar, activá tu cuenta creando tu contraseña.")
            .contains("href=\"https://app.test/login?email=adm%40x.test\"")
            .contains(">Activar mi cuenta</a>");
    }

    @Test
    @DisplayName("bienvenida de empleado (individual y batch): layout y copy")
    void welcomeEmployee() {
        WelcomeEmails emails = new WelcomeEmails(emailService, props);
        emails.sendEmployeeWelcome("emp@x.test");
        String html = sentHtml("emp@x.test", "Bienvenido a Arias — Activá tu cuenta");
        assertThat(html).contains(BAND).contains("andá preparando la servilleta").contains(">Activar mi cuenta</a>");

        emails.sendEmployeeWelcomeBatch(List.of("b1@x.test"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutgoingEmail>> batch = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendBatch(batch.capture());
        assertThat(batch.getValue()).singleElement().satisfies(m -> {
            assertThat(m.html()).contains("color-scheme");
            assertThat(m.to()).isEqualTo("b1@x.test");
            assertThat(m.html()).contains(BAND).contains("email=b1%40x.test");
        });
    }

    private DailyChoice choice(String firstName, String dish, String side) {
        User user = mock(User.class);
        when(user.getFirstName()).thenReturn(firstName);
        when(user.getEmail()).thenReturn("emp@x.test");
        DailyChoice order = mock(DailyChoice.class);
        when(order.getUser()).thenReturn(user);
        when(order.getDishNombre()).thenReturn(dish);
        when(order.getSideNombre()).thenReturn(side);
        when(order.getFecha()).thenReturn(LocalDate.of(2026, 10, 17));
        return order;
    }

    @Test
    @DisplayName("pedido mantenido: layout, copy, botón y nombres escapados")
    void orderKept() {
        new DisableNotificationEmails(emailService, props)
            .notifyOrderKept(choice("Ana<i>", "Milanesa <b>x</b>", "Puré"),
                DisableNotificationEmails.Reason.DISH_DISABLED, LocalTime.of(10, 30));
        String html = sentHtml("emp@x.test", "El plato que elegiste hoy ya no está disponible");
        assertThat(html).contains(BAND)
            .contains(">¡Hola Ana&lt;i&gt;!</h2>")
            .contains("Te queríamos avisar que <strong>Milanesa &lt;b&gt;x&lt;/b&gt;</strong>, que elegiste para hoy, ya no está disponible.")
            .contains("podés entrar a la app antes de las <strong>10:30</strong>.")
            .contains("href=\"https://app.test\"")
            .contains(">Modificar mi pedido</a>")
            .doesNotContain("<b>x</b>");
    }

    @Test
    @DisplayName("cancelación por cambio de almuerzo: layout, copy y botón")
    void cancellation() {
        new DisableNotificationEmails(emailService, props)
            .notifySpecialRemovedForDate(choice("Ana", "Milanesa", null));
        String html = sentHtml("emp@x.test", "Cambio en tu almuerzo del 17/10");
        assertThat(html).contains(BAND)
            .contains(">Hola Ana</h2>")
            .contains("Lamentablemente tenemos que pedirte que cambies tu plato programado para el <strong>17/10</strong>.")
            .contains("Ya están disponibles en la app las opciones de cambio.")
            .contains("Con tu permiso, muchas gracias!")
            .contains(">Programar pedido</a>");
    }
}
