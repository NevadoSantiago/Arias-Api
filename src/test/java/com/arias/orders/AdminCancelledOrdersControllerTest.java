package com.arias.orders;

import com.arias.auth.JwtService;
import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/admin/orders/by-pickup/cancelled}: the read-only list of cancelled orders for the board. */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(AdminCancelledOrdersControllerTest.FixedClockConfig.class)
class AdminCancelledOrdersControllerTest {

    static final Instant NOW = Instant.parse("2026-03-10T13:00:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    static final String URL = "/api/v1/admin/orders/by-pickup/cancelled";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(NOW, ZONE);
        }
    }

    @Autowired private WebApplicationContext webContext;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private OrderRepository orderRepo;

    private MockMvc mvc;
    private Category category;
    private MenuSection section;
    private User customer;
    private String admin;
    private String employee;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
        section = menuSectionRepo.save(MenuSection.builder()
            .nombre("seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        customer = userRepo.save(User.builder()
            .email("c-" + System.nanoTime() + "@test.arias.com").role(Role.EMPLOYEE)
            .nickname("Coty").active(true).build());
        User adminUser = userRepo.save(User.builder().email("adm-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x").role(Role.SUPER_ADMIN).active(true).build());
        admin = "Bearer " + jwtService.issueAccessToken(adminUser);
        employee = "Bearer " + jwtService.issueAccessToken(customer);
    }

    private Order order(LocalDate fecha, OrderEstado estado, CancelReason reason, Boolean refunded,
                        Instant cancelledAt, Instant comandadoAt) {
        Dish dish = dishRepo.save(Dish.builder().nombre("Plato-" + System.nanoTime()).category(category)
            .menuSection(section).enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
        Order o = Order.builder().user(customer).fecha(fecha).pickupAt(NOW.plus(2, ChronoUnit.HOURS))
            .estado(estado).creditTotal(1).notas("sin sal").cancelReason(reason).cancelRefunded(refunded)
            .cancelledAt(cancelledAt).comandadoAt(comandadoAt).build();
        o.addItem(OrderItem.builder().dish(dish).category(category).dishNombre(dish.getNombre())
            .dishCategoria(category.getNombre()).creditCost(1).sideNombre("Papas").build());
        return orderRepo.save(o);
    }

    @Test
    @DisplayName("lists today's cancelled orders newest first, with the fields the board needs")
    void listsCancelledOrdersNewestFirst() throws Exception {
        Order older = order(TODAY, OrderEstado.CANCELADO, CancelReason.CUSTOMER, true,
            NOW.minus(30, ChronoUnit.MINUTES), null);
        Order newer = order(TODAY, OrderEstado.CANCELADO, CancelReason.CUSTOMER, false,
            NOW.minus(5, ChronoUnit.MINUTES), NOW.minus(1, ChronoUnit.HOURS));

        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(newer.getId()))
            .andExpect(jsonPath("$[0].customerNickname").value("Coty"))
            .andExpect(jsonPath("$[0].items[0].sideNombre").value("Papas"))
            .andExpect(jsonPath("$[0].notas").value("sin sal"))
            .andExpect(jsonPath("$[0].pickupAt").exists())
            .andExpect(jsonPath("$[0].comandadoAt").exists())
            .andExpect(jsonPath("$[0].cancelledAt").exists())
            .andExpect(jsonPath("$[0].cancelRefunded").value(false))
            .andExpect(jsonPath("$[0].cancelReason").value("CUSTOMER"))
            .andExpect(jsonPath("$[1].id").value(older.getId()))
            .andExpect(jsonPath("$[1].comandadoAt").doesNotExist())
            .andExpect(jsonPath("$[1].cancelRefunded").value(true));
    }

    @Test
    @DisplayName("excludes PAYMENT_FAILED cancellations, keeps DISH_REMOVED and legacy rows without a reason")
    void excludesPaymentFailedOnly() throws Exception {
        order(TODAY, OrderEstado.CANCELADO, CancelReason.PAYMENT_FAILED, true, NOW, null);
        Order dishRemoved = order(TODAY, OrderEstado.CANCELADO, CancelReason.DISH_REMOVED, true,
            NOW.minus(1, ChronoUnit.MINUTES), null);
        Order legacy = order(TODAY, OrderEstado.CANCELADO, null, true, NOW.minus(2, ChronoUnit.MINUTES), null);

        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(dishRemoved.getId()))
            .andExpect(jsonPath("$[1].id").value(legacy.getId()));
    }

    @Test
    @DisplayName("only CANCELADO orders of the requested day: defaults to today, accepts ?fecha=")
    void filtersByDayAndState() throws Exception {
        order(TODAY, OrderEstado.CONFIRMADO, null, null, null, null);
        Order tomorrow = order(TODAY.plusDays(1), OrderEstado.CANCELADO, CancelReason.CUSTOMER, true, NOW, null);
        order(TODAY, OrderEstado.CANCELADO, CancelReason.CUSTOMER, true, NOW, null);

        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(URL).param("fecha", "2026-03-11").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(tomorrow.getId()));
    }

    @Test
    @DisplayName("a non-admin gets 403 and an anonymous caller 401")
    void requiresSuperAdmin() throws Exception {
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, employee)).andExpect(status().isForbidden());
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }
}
