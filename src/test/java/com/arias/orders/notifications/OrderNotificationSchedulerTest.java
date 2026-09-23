package com.arias.orders.notifications;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.email.EmailService;
import com.arias.orders.Order;
import com.arias.orders.OrderDto;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderPlacementService;
import com.arias.orders.OrderRepository;
import com.arias.orders.PlaceOrderV2Request;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unidad 12 — {@link OrderNotificationScheduler}: resumen matutino, alerta
 * de cancelación y recordatorio de retiro (spec {@code order-notifications}
 * completa, diseño §Decisión 11). {@code now} fijo, {@link EmailService}
 * mockeado. {@code OrderReminderScheduler} (recordatorio B2B) NO se toca ni
 * se testea acá.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@RecordApplicationEvents
@Import(OrderNotificationSchedulerTest.FixedClockConfig.class)
class OrderNotificationSchedulerTest {

    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    // 08:02 ART — dentro de la ventana [08:00, 08:05) del daily_summary_time
    // default (V20).
    static final Instant FIXED_NOW = Instant.parse("2026-03-10T11:02:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired
    private OrderNotificationScheduler scheduler;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private NotificationRunLogRepository runLogRepo;

    @Autowired
    private OrderPlacementService orderPlacementService;

    @Autowired
    private CategoryRepository categoryRepo;

    @Autowired
    private MenuSectionRepository menuSectionRepo;

    @Autowired
    private DishRepository dishRepo;

    @Autowired
    private CreditWalletRepository walletRepo;

    /** Ver el comentario equivalente en {@code OrderPlacementServiceTest}: el
     *  UPDATE en bloque de {@code claimReminderSlot} no refresca el
     *  first-level cache — hace falta limpiar el contexto para releer. */
    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private EmailService emailService;

    // ─── helpers ────────────────────────────────────────────────────────────

    private User persistSuperAdmin() {
        return userRepo.save(User.builder()
            .email("admin-" + System.nanoTime() + "@test.arias.com")
            .role(Role.SUPER_ADMIN)
            .active(true)
            .build());
    }

    private User persistCustomer() {
        return userRepo.save(User.builder()
            .email("cliente-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .nickname("Cliente")
            .active(true)
            .emailVerifiedAt(Instant.now()) // gate de email-not-verified: no es lo que testea esta suite
            .build());
    }

    private Order persistOrder(User user, Instant pickupAt, OrderEstado estado, int creditTotal) {
        return orderRepo.save(Order.builder()
            .user(user)
            .fecha(LocalDate.ofInstant(pickupAt, ZONE))
            .pickupAt(pickupAt)
            .estado(estado)
            .creditTotal(creditTotal)
            .build());
    }

    // ─── Resumen matutino ───────────────────────────────────────────────────

    @Test
    @DisplayName("sendDailySummaryIfDue(): agrupa los pedidos del día por horario de retiro y excluye CANCELADO")
    void resumenMatutinoAgrupaPorHorarioDeRetiroYExcluyeCancelados() {
        User admin = persistSuperAdmin();
        User customer = persistCustomer();
        persistOrder(customer, FIXED_NOW.plus(3, ChronoUnit.HOURS), OrderEstado.PENDIENTE, 2);   // 11:02 ART
        persistOrder(customer, FIXED_NOW.plus(3, ChronoUnit.HOURS), OrderEstado.PENDIENTE, 1);   // mismo horario, otro pedido
        persistOrder(customer, FIXED_NOW.plus(4, ChronoUnit.HOURS), OrderEstado.PENDIENTE, 5);   // 12:02 ART, otro grupo
        persistOrder(customer, FIXED_NOW.plus(3, ChronoUnit.HOURS), OrderEstado.CANCELADO, 99);  // excluido

        scheduler.sendDailySummaryIfDue();

        org.mockito.ArgumentCaptor<String> htmlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailService).send(eq(admin.getEmail()), anyString(), htmlCaptor.capture());
        String html = htmlCaptor.getValue();
        assertThat(html).contains("11:02").contains("12:02");
        // 3 pedidos activos (2 + 1 en el mismo horario, 1 en otro) — el CANCELADO no cuenta.
        assertThat(html).contains("3 pedido(s) en total");

        assertThat(runLogRepo.existsById(new NotificationRunLogId("DAILY_SUMMARY", LocalDate.of(2026, 3, 10))))
            .isTrue();
    }

    @Test
    @DisplayName("sendDailySummaryIfDue(): el dedup por (tipo, fecha) evita reenviar en un segundo tick")
    void resumenMatutinoNoReenviaElMismoDia() {
        User admin = persistSuperAdmin();
        User customer = persistCustomer();
        persistOrder(customer, FIXED_NOW.plus(2, ChronoUnit.HOURS), OrderEstado.PENDIENTE, 1);

        scheduler.sendDailySummaryIfDue();
        scheduler.sendDailySummaryIfDue(); // segundo tick del mismo cron, mismo día

        verify(emailService, times(1)).send(eq(admin.getEmail()), anyString(), anyString());
    }

    // ─── Alerta de cancelación ──────────────────────────────────────────────

    @Test
    @DisplayName("onOrderCancelled(): manda la alerta a los administradores Y al cliente que canceló")
    void alertaDeCancelacionVaAAdministradoresYCliente() {
        User admin = persistSuperAdmin();
        OrderCancelledEvent event = new OrderCancelledEvent(
            42L, 7L, "cliente-cancela@test.arias.com", "Cliente",
            FIXED_NOW.plus(2, ChronoUnit.HOURS), 3);

        scheduler.onOrderCancelled(event);

        verify(emailService).send(eq(admin.getEmail()), contains("#42"), anyString());
        verify(emailService).send(eq("cliente-cancela@test.arias.com"), contains("#42"), anyString());
    }

    @Test
    @DisplayName("OrderPlacementService.cancel(): publica OrderCancelledEvent dentro de la transacción")
    void cancelPublicaElEventoDeCancelacion(ApplicationEvents events) {
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(2).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        Dish dish = dishRepo.save(Dish.builder()
            .nombre("Plato-" + System.nanoTime()).category(category).menuSection(section)
            .enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
        User user = persistCustomer();
        walletRepo.saveAndFlush(CreditWallet.builder().userId(user.getId()).available(10).committed(0).build());

        Instant pickupAt = FIXED_NOW.plus(3, ChronoUnit.HOURS); // 11:02 ART, dentro de la ventana 11:00-15:00
        OrderDto placed = orderPlacementService.place(user.getId(), new PlaceOrderV2Request(
            List.of(new PlaceOrderV2Request.OrderItemRequest(dish.getId(), null, null)), pickupAt, null));

        orderPlacementService.cancel(user.getId(), placed.id());

        List<OrderCancelledEvent> published = events.stream(OrderCancelledEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).orderId()).isEqualTo(placed.id());
        assertThat(published.get(0).userEmail()).isEqualTo(user.getEmail());
        assertThat(published.get(0).creditTotal()).isEqualTo(2);
    }

    // ─── Recordatorio de retiro ─────────────────────────────────────────────

    @Test
    @DisplayName("sendPickupReminders(): envía en la ventana configurada y omite CANCELADO")
    void recordatorioSeEnviaEnVentanaYOmiteCancelados() {
        User dueUser = persistCustomer();
        User notYetUser = persistCustomer();
        User cancelledUser = persistCustomer();

        // pickup_reminder_minutes default = 25 (V20): pickupAt - 25 <= now <=> pickupAt <= now + 25.
        Order due = persistOrder(dueUser, FIXED_NOW.plus(25, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, 1);
        Order notYetDue = persistOrder(notYetUser, FIXED_NOW.plus(40, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, 1);
        Order cancelled = persistOrder(cancelledUser, FIXED_NOW.plus(10, ChronoUnit.MINUTES), OrderEstado.CANCELADO, 1);

        scheduler.sendPickupReminders();

        verify(emailService).send(eq(dueUser.getEmail()), anyString(), anyString());
        verify(emailService, never()).send(eq(notYetUser.getEmail()), anyString(), anyString());
        verify(emailService, never()).send(eq(cancelledUser.getEmail()), anyString(), anyString());

        entityManager.clear();
        assertThat(orderRepo.findById(due.getId()).orElseThrow().getReminderSentAt()).isNotNull();
        assertThat(orderRepo.findById(notYetDue.getId()).orElseThrow().getReminderSentAt()).isNull();
        assertThat(orderRepo.findById(cancelled.getId()).orElseThrow().getReminderSentAt()).isNull();
    }

    @Test
    @DisplayName("sendPickupReminders(): el claim atómico por pedido evita reenviar en un segundo tick")
    void recordatorioNoReenviaElMismoPedido() {
        User user = persistCustomer();
        persistOrder(user, FIXED_NOW.plus(25, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, 1);

        scheduler.sendPickupReminders();
        scheduler.sendPickupReminders(); // segundo tick del minuto siguiente

        verify(emailService, times(1)).send(eq(user.getEmail()), anyString(), anyString());
    }
}
