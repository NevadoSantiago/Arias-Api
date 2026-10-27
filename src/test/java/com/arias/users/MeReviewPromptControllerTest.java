package com.arias.users;

import com.arias.auth.JwtService;
import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderItem;
import com.arias.orders.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /api/v1/me/review-prompt}: the in-app Google review card. */
@SpringBootTest
@Transactional
class MeReviewPromptControllerTest {

    private static final String URL = "/api/v1/me/review-prompt";
    private static final String REVIEW_URL =
        "https://search.google.com/local/writereview?placeid=ChIJL1FvGqG2vJURKiXZNso-5u8";

    @Autowired private WebApplicationContext webContext;
    @Autowired private UserRepository userRepo;
    @Autowired private JwtService jwtService;
    @Autowired private OrderRepository orderRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private CompanyRepository companyRepo;

    private MockMvc mockMvc;
    private Category category;
    private MenuSection section;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        category = categoryRepo.save(Category.builder()
            .nombre("Cat-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
        section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Sec-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
    }

    private User newUser() {
        return userRepo.save(User.builder()
            .email("review-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x").role(Role.EMPLOYEE).active(true).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private Order order(User user, OrderEstado estado, Instant deliveredAt, String... dishNames) {
        Order order = Order.builder().user(user).fecha(LocalDate.of(2026, 3, 10))
            .pickupAt(Instant.parse("2026-03-10T16:00:00Z")).estado(estado)
            .creditTotal(dishNames.length).deliveredAt(deliveredAt).build();
        for (String name : dishNames) {
            Dish dish = dishRepo.save(Dish.builder().nombre(name).category(category).menuSection(section)
                .enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
            order.addItem(OrderItem.builder().dish(dish).dishNombre(name)
                .dishCategoria(category.getNombre()).creditCost(1).build());
        }
        return orderRepo.save(order);
    }

    private void expectHidden(User user) throws Exception {
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.show").value(false))
            .andExpect(jsonPath("$.dishName").value((Object) null))
            .andExpect(jsonPath("$.reviewUrl").value((Object) null));
    }

    @Test
    @DisplayName("shows the dish of the first item of the latest ENTREGADO order, ignoring other states")
    void showsLatestDeliveredDish() throws Exception {
        User user = newUser();
        order(user, OrderEstado.ENTREGADO, Instant.parse("2026-03-09T16:00:00Z"), "Plato viejo");
        order(user, OrderEstado.ENTREGADO, Instant.parse("2026-03-10T16:00:00Z"), "Milanesa", "Flan");
        order(user, OrderEstado.COMANDADO, null, "Plato comandado");
        order(user, OrderEstado.PENDIENTE, null, "Plato pendiente");

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.show").value(true))
            .andExpect(jsonPath("$.dishName").value("Milanesa"))
            .andExpect(jsonPath("$.reviewUrl").value(REVIEW_URL));
    }

    @Test
    @DisplayName("hidden without an ENTREGADO order")
    void hiddenWithoutDelivered() throws Exception {
        User user = newUser();
        order(user, OrderEstado.COMANDADO, null, "Algo");

        expectHidden(user);
    }

    @Test
    @DisplayName("hidden for company (B2B) users")
    void hiddenForCompanyUsers() throws Exception {
        User user = newUser();
        user.setCompany(companyRepo.save(Company.builder()
            .nombre("Empresa-" + System.nanoTime())
            .cuit(String.valueOf(20_000_000_000L + (System.nanoTime() % 9_000_000_000L)))
            .calle("Calle").altura("1").horaEntrega(LocalTime.of(13, 0))
            .categoriaDefault(category).enabled(true).build()));
        userRepo.saveAndFlush(user);
        order(user, OrderEstado.ENTREGADO, Instant.parse("2026-03-10T16:00:00Z"), "Algo");

        expectHidden(user);
    }

    @Test
    @DisplayName("opened and never are idempotent, return 204 and hide the card")
    void transitions() throws Exception {
        User user = newUser();
        order(user, OrderEstado.ENTREGADO, Instant.parse("2026-03-10T16:00:00Z"), "Algo");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(URL + "/opened").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());
        }
        assertThat(userRepo.findById(user.getId()).orElseThrow().getReviewPromptStatus())
            .isEqualTo(ReviewPromptStatus.OPENED);
        expectHidden(user);

        mockMvc.perform(post(URL + "/never").header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isNoContent());
        assertThat(userRepo.findById(user.getId()).orElseThrow().getReviewPromptStatus())
            .isEqualTo(ReviewPromptStatus.NEVER);
    }

    @Test
    @DisplayName("NEVER is never downgraded by OPENED")
    void openedDoesNotDowngradeNever() throws Exception {
        User user = newUser();

        mockMvc.perform(post(URL + "/never").header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isNoContent());
        mockMvc.perform(post(URL + "/opened").header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isNoContent());

        assertThat(userRepo.findById(user.getId()).orElseThrow().getReviewPromptStatus())
            .isEqualTo(ReviewPromptStatus.NEVER);
    }

    @Test
    @DisplayName("an unknown action is a 400")
    void unknownAction() throws Exception {
        mockMvc.perform(post(URL + "/whatever").header(HttpHeaders.AUTHORIZATION, bearer(newUser())))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unauthenticated requests get 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/opened")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/never")).andExpect(status().isUnauthorized());
    }
}
