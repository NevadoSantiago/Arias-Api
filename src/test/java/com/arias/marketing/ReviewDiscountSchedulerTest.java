package com.arias.marketing;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.credits.discount.AccountDiscount;
import com.arias.credits.discount.AccountDiscountRepository;
import com.arias.credits.discount.AccountDiscountService;
import com.arias.email.EmailService;
import com.arias.email.OutgoingEmail;
import com.arias.orders.notifications.NotificationRunLogRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderItem;
import com.arias.orders.OrderRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Daily 10:00 campaign: gives the account discount to B2C customers who ate yesterday and
 * mails them once. EmailService is mocked: no real email ever leaves the test.
 */
@SpringBootTest(properties = {
    "spring.main.allow-bean-definition-overriding=true",
    "arias.marketing.review-campaign-enabled=true"
})
@Transactional
@Import(ReviewDiscountSchedulerTest.FixedClockConfig.class)
class ReviewDiscountSchedulerTest {

    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    // 10:00 ART on 2026-03-11; the campaign targets 2026-03-10.
    static final Instant FIXED_NOW = Instant.parse("2026-03-11T13:00:00Z");
    static final LocalDate YESTERDAY = LocalDate.of(2026, 3, 10);
    private static final AtomicLong PHONE_SEQ = new AtomicLong(5_000);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired private ReviewDiscountScheduler scheduler;
    @Autowired private UserRepository userRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private CompanyRepository companyRepo;
    @Autowired private AccountDiscountRepository discountRepo;
    @Autowired private AccountDiscountService discountService;
    @Autowired private NotificationRunLogRepository runLogRepo;

    @MockitoBean
    private EmailService emailService;

    private Category category;
    private MenuSection section;

    @BeforeEach
    void setUp() {
        category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
        section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
    }

    // ─── fixtures ───────────────────────────────────────────────────────────

    private User newUser() {
        return userRepo.save(User.builder()
            .email("campaign-" + System.nanoTime() + "@test.arias.com")
            .firstName("Ana")
            .role(Role.EMPLOYEE)
            .active(true)
            .phone("+549" + (1155770100L + PHONE_SEQ.incrementAndGet()))
            .build());
    }

    private Dish dish(String nombre) {
        return dishRepo.save(Dish.builder()
            .nombre(nombre).category(category).menuSection(section)
            .enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
    }

    private Order order(User user, LocalDate fecha, OrderEstado estado, LocalTime pickup, String... dishNames) {
        Order order = Order.builder()
            .user(user)
            .fecha(fecha)
            .pickupAt(fecha.atTime(pickup).atZone(ZONE).toInstant())
            .estado(estado)
            .creditTotal(dishNames.length)
            .build();
        for (String name : dishNames) {
            order.addItem(OrderItem.builder()
                .dish(dish(name)).dishNombre(name).dishCategoria(category.getNombre()).creditCost(1).build());
        }
        return orderRepo.save(order);
    }

    private Order order(User user, LocalDate fecha, OrderEstado estado, String... dishNames) {
        return order(user, fecha, estado, LocalTime.of(13, 0), dishNames);
    }

    private List<OutgoingEmail> sentEmails() {
        ArgumentCaptor<List<OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(emailService, org.mockito.Mockito.atLeast(0)).sendBatch(captor.capture());
        List<OutgoingEmail> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        return all;
    }

    // ─── targeting ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("grants the discount and mails a B2C customer who ate yesterday (ENTREGADO), naming the dish")
    void grantsAndMails() {
        User user = newUser();
        order(user, YESTERDAY, OrderEstado.ENTREGADO, "Milanesa napolitana");

        scheduler.runDaily();

        AccountDiscount d = discountRepo.findByUserId(user.getId()).orElseThrow();
        assertThat(d.getPercent()).isEqualTo(10);
        assertThat(d.getExpiresAt()).isEqualTo(FIXED_NOW.plus(AccountDiscountService.VALIDITY));
        List<OutgoingEmail> emails = sentEmails();
        assertThat(emails).hasSize(1);
        assertThat(emails.get(0).to()).isEqualTo(user.getEmail());
        assertThat(emails.get(0).subject()).contains("sorpresa");
        assertThat(emails.get(0).html()).contains("Milanesa napolitana");
    }

    @Test
    @DisplayName("COMANDADO yesterday also counts")
    void comandadoCounts() {
        User user = newUser();
        order(user, YESTERDAY, OrderEstado.COMANDADO, "Pastel de papas");

        scheduler.runDaily();

        assertThat(discountRepo.findByUserId(user.getId())).isPresent();
    }

    @Test
    @DisplayName("ignores orders that were not eaten or are not from yesterday")
    void ignoresOtherOrders() {
        User pending = newUser();
        order(pending, YESTERDAY, OrderEstado.PENDIENTE, "A");
        User cancelled = newUser();
        order(cancelled, YESTERDAY, OrderEstado.CANCELADO, "B");
        User awaitingPayment = newUser();
        order(awaitingPayment, YESTERDAY, OrderEstado.PENDIENTE_PAGO, "C");
        User today = newUser();
        order(today, YESTERDAY.plusDays(1), OrderEstado.ENTREGADO, "D");
        User twoDaysAgo = newUser();
        order(twoDaysAgo, YESTERDAY.minusDays(1), OrderEstado.ENTREGADO, "E");

        scheduler.runDaily();

        assertThat(discountRepo.count()).isZero();
        verify(emailService, never()).sendBatch(anyList());
    }

    @Test
    @DisplayName("company (B2B) orders are excluded")
    void companyOrdersExcluded() {
        User employee = newUser();
        Company company = companyRepo.save(Company.builder()
            .nombre("Empresa-" + System.nanoTime())
            .cuit(String.valueOf(20_000_000_000L + (System.nanoTime() % 9_000_000_000L)))
            .calle("Calle Falsa").altura("123").horaEntrega(LocalTime.of(13, 0))
            .categoriaDefault(category).enabled(true).build());
        Order order = order(employee, YESTERDAY, OrderEstado.ENTREGADO, "Fideos");
        order.setCompany(company);
        orderRepo.saveAndFlush(order);

        scheduler.runDaily();

        assertThat(discountRepo.findByUserId(employee.getId())).isEmpty();
    }

    @Test
    @DisplayName("a customer who already has an account discount (even used) gets neither a new one nor a mail")
    void existingDiscountExcluded() {
        User user = newUser();
        discountService.grantIfAbsent(user, 10, "earlier");
        order(user, YESTERDAY, OrderEstado.ENTREGADO, "Fideos");

        scheduler.runDaily();

        verify(emailService, never()).sendBatch(anyList());
        assertThat(discountRepo.findByUserId(user.getId()).orElseThrow().getSource()).isEqualTo("earlier");
    }

    @Test
    @DisplayName("soft-deleted users are excluded")
    void deletedUsersExcluded() {
        User user = newUser();
        order(user, YESTERDAY, OrderEstado.ENTREGADO, "Fideos");
        user.setDeletedAt(FIXED_NOW);
        userRepo.saveAndFlush(user);

        scheduler.runDaily();

        assertThat(discountRepo.findByUserId(user.getId())).isEmpty();
        verify(emailService, never()).sendBatch(anyList());
    }

    @Test
    @DisplayName("a customer who opted out of news still gets the discount but no mail")
    void optedOutGetsDiscountWithoutMail() {
        User user = newUser();
        user.setRecibeNovedades(false);
        userRepo.saveAndFlush(user);
        order(user, YESTERDAY, OrderEstado.ENTREGADO, "Fideos");

        scheduler.runDaily();

        assertThat(discountRepo.findByUserId(user.getId())).isPresent();
        verify(emailService, never()).sendBatch(anyList());
    }

    @Test
    @DisplayName("the dish is the first item of the latest order of the day")
    void dishComesFromLatestOrder() {
        User user = newUser();
        order(user, YESTERDAY, OrderEstado.ENTREGADO, LocalTime.of(12, 0), "Plato temprano");
        order(user, YESTERDAY, OrderEstado.ENTREGADO, LocalTime.of(14, 0), "Plato tardio", "Segundo plato");

        scheduler.runDaily();

        List<OutgoingEmail> emails = sentEmails();
        assertThat(emails).hasSize(1); // one mail per customer, not per order
        assertThat(emails.get(0).html()).contains("Plato tardio")
            .doesNotContain("Plato temprano").doesNotContain("Segundo plato");
    }

    @Test
    @DisplayName("user data is HTML-escaped in the mail")
    void escapesDishName() {
        User user = newUser();
        order(user, YESTERDAY, OrderEstado.ENTREGADO, "<script>alert(1)</script>");

        scheduler.runDaily();

        String html = sentEmails().get(0).html();
        assertThat(html).doesNotContain("<script>alert(1)").contains("&lt;script&gt;");
    }

    // ─── idempotency and batching ───────────────────────────────────────────

    @Test
    @DisplayName("runs once per day: a second run sends nothing")
    void runsOncePerDay() {
        User first = newUser();
        order(first, YESTERDAY, OrderEstado.ENTREGADO, "Fideos");
        scheduler.runDaily();
        User late = newUser();
        order(late, YESTERDAY, OrderEstado.ENTREGADO, "Ravioles");

        scheduler.runDaily();

        verify(emailService, times(1)).sendBatch(anyList());
        assertThat(discountRepo.findByUserId(late.getId())).isEmpty();
    }

    @Test
    @DisplayName("sends in chunks of at most 100 emails")
    void sendsInChunksOf100() {
        for (int i = 0; i < 101; i++) {
            order(newUser(), YESTERDAY, OrderEstado.ENTREGADO, "Plato");
        }

        scheduler.runDaily();

        ArgumentCaptor<List<OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(emailService, times(2)).sendBatch(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactlyInAnyOrder(100, 1);
    }
}
