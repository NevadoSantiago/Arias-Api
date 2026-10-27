package com.arias.users;

import com.arias.auth.JwtService;
import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderItem;
import com.arias.orders.OrderRepository;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "arias.marketing.google-review-url=")
@Transactional
class MeReviewPromptBlankUrlTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private UserRepository userRepo;
    @Autowired private JwtService jwtService;
    @Autowired private OrderRepository orderRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;

    @Test
    @DisplayName("hidden when no review URL is configured")
    void hiddenWithoutUrl() throws Exception {
        User user = userRepo.save(User.builder().email("blank-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x").role(Role.EMPLOYEE).active(true).build());
        Category category = categoryRepo.save(Category.builder()
            .nombre("Cat-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Sec-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        Dish dish = dishRepo.save(Dish.builder().nombre("Algo").category(category).menuSection(section)
            .enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
        Order order = Order.builder().user(user).fecha(LocalDate.of(2026, 3, 10))
            .pickupAt(Instant.parse("2026-03-10T16:00:00Z")).estado(OrderEstado.ENTREGADO)
            .creditTotal(1).deliveredAt(Instant.parse("2026-03-10T16:00:00Z")).build();
        order.addItem(OrderItem.builder().dish(dish).dishNombre("Algo")
            .dishCategoria(category.getNombre()).creditCost(1).build());
        orderRepo.save(order);

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        mockMvc.perform(get("/api/v1/me/review-prompt")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issueAccessToken(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.show").value(false))
            .andExpect(jsonPath("$.reviewUrl").value((Object) null));
    }
}
