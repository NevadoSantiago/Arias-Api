package com.arias.orders;

import com.arias.auth.JwtService;
import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.dishes.DishStation;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.printing.PrintJobRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Comandar ahora": PUT /comandado accepts PENDIENTE orders, SUPER_ADMIN only. */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(ComandarAhoraEndpointTest.FixedClockConfig.class)
class ComandarAhoraEndpointTest {

    static final Instant NOW = Instant.parse("2026-03-10T13:00:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

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
    @Autowired private PrintJobRepository jobRepo;
    @Autowired private CreditWalletRepository walletRepo;
    @Autowired private CreditMovementRepository movementRepo;

    private MockMvc mvc;
    private User customer;
    private String admin;
    private String employee;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        customer = userRepo.save(User.builder()
            .email("c-" + System.nanoTime() + "@test.arias.com").role(Role.EMPLOYEE)
            .nickname("Coty").active(true).build());
        User adminUser = userRepo.save(User.builder().email("adm-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x").role(Role.SUPER_ADMIN).active(true).build());
        admin = "Bearer " + jwtService.issueAccessToken(adminUser);
        employee = "Bearer " + jwtService.issueAccessToken(customer);
    }

    private Order order(OrderEstado estado) {
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(2).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        Dish d = dishRepo.save(Dish.builder().nombre("Plato-" + System.nanoTime()).category(category)
            .menuSection(section).station(DishStation.COCINA).enabled(true).especial(false)
            .stockDiarioDefault(5).stockActual(5).build());
        Order o = Order.builder().user(customer).fecha(LocalDate.of(2026, 3, 10))
            .pickupAt(Instant.parse("2026-03-10T16:30:00Z")).estado(estado).creditTotal(2).build();
        o.addItem(OrderItem.builder().dish(d).category(category).dishNombre(d.getNombre())
            .dishCategoria(category.getNombre()).creditCost(2).build());
        return orderRepo.save(o);
    }

    private void seedWallet(int committed) {
        walletRepo.saveAndFlush(CreditWallet.builder()
            .userId(customer.getId()).available(0).committed(committed).build());
    }

    @Test
    @DisplayName("PUT /{id}/comandado on a PENDIENTE order consumes credits once and enqueues the print jobs")
    void comandarAhoraPendiente() throws Exception {
        Order o = order(OrderEstado.PENDIENTE);
        seedWallet(2);

        mvc.perform(put("/api/v1/admin/orders/" + o.getId() + "/comandado").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.estado").value("COMANDADO"));

        assertThat(walletRepo.findByIdForUpdate(customer.getId()).orElseThrow().getCommitted()).isZero();
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(customer.getId())).hasSize(1);
        assertThat(jobRepo.findByOrderId(o.getId())).hasSize(1);
    }

    @Test
    @DisplayName("PUT /comandado batch accepts PENDIENTE and CONFIRMADO orders together")
    void comandarBatchMixed() throws Exception {
        Order pending = order(OrderEstado.PENDIENTE);
        Order confirmed = order(OrderEstado.CONFIRMADO);
        seedWallet(2);

        mvc.perform(put("/api/v1/admin/orders/comandado").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderIds\":[" + pending.getId() + "," + confirmed.getId() + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(customer.getId())).hasSize(1);
        assertThat(jobRepo.findByOrderId(pending.getId())).hasSize(1);
        assertThat(jobRepo.findByOrderId(confirmed.getId())).hasSize(1);
    }

    @Test
    @DisplayName("PUT /{id}/comandado twice does not consume or enqueue again (409 on the second call)")
    void comandarTwiceIsIdempotent() throws Exception {
        Order o = order(OrderEstado.PENDIENTE);
        seedWallet(2);
        mvc.perform(put("/api/v1/admin/orders/" + o.getId() + "/comandado").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk());

        mvc.perform(put("/api/v1/admin/orders/" + o.getId() + "/comandado").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isConflict());

        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(customer.getId())).hasSize(1);
        assertThat(jobRepo.findByOrderId(o.getId())).hasSize(1);
    }

    @Test
    @DisplayName("PUT /{id}/comandado returns 409 for ENTREGADO, CANCELADO and PENDIENTE_PAGO")
    void comandarRejectsOtherStates() throws Exception {
        for (OrderEstado estado : new OrderEstado[] {OrderEstado.ENTREGADO, OrderEstado.CANCELADO,
                OrderEstado.PENDIENTE_PAGO}) {
            Order o = order(estado);
            mvc.perform(put("/api/v1/admin/orders/" + o.getId() + "/comandado")
                    .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());
        }
    }

    @Test
    @DisplayName("PUT /{id}/comandado is SUPER_ADMIN only")
    void comandarRequiresSuperAdmin() throws Exception {
        Order o = order(OrderEstado.PENDIENTE);

        mvc.perform(put("/api/v1/admin/orders/" + o.getId() + "/comandado").header(HttpHeaders.AUTHORIZATION, employee))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/orders/" + o.getId() + "/comandado"))
            .andExpect(status().isUnauthorized());
    }
}
