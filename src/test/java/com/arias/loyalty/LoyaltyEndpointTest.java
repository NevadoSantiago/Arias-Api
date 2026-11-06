package com.arias.loyalty;

import com.arias.auth.JwtService;
import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

import static com.arias.loyalty.LoyaltyTestSupport.delivered;
import static com.arias.loyalty.LoyaltyTestSupport.newUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/loyalty}. */
@SpringBootTest(properties = "arias.loyalty.enabled=true")
@Transactional
class LoyaltyEndpointTest {

    private static final String URL = "/api/v1/loyalty";

    @Autowired private WebApplicationContext webContext;
    @Autowired private UserRepository userRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private JwtService jwtService;
    @Autowired private LoyaltyService loyaltyService;
    @Autowired private LoyaltyRewardRepository rewardRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private CompanyRepository companyRepo;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    @Test
    @DisplayName("requires authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a customer without deliveries has no cycle and is not waiting; every milestone is locked")
    void noDeliveries() throws Exception {
        User user = newUser(userRepo);

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.cycle").value((Object) null))
            .andExpect(jsonPath("$.waiting").value(false))
            .andExpect(jsonPath("$.pendingDessert").value((Object) null))
            .andExpect(jsonPath("$.milestones.length()").value(3))
            .andExpect(jsonPath("$.milestones[0].lunches").value(6))
            .andExpect(jsonPath("$.milestones[0].kind").value("DESSERT"))
            .andExpect(jsonPath("$.milestones[0].status").value("LOCKED"))
            .andExpect(jsonPath("$.milestones[1].lunches").value(18))
            .andExpect(jsonPath("$.milestones[1].kind").value("LUNCH"))
            .andExpect(jsonPath("$.milestones[2].lunches").value(24))
            .andExpect(jsonPath("$.milestones[2].kind").value("DISCOUNT"))
            .andExpect(jsonPath("$.milestones[2].percent").value(10));
    }

    @Test
    @DisplayName("progress and an earned dessert show up as the pending dessert")
    void progressAndPendingDessert() throws Exception {
        User user = newUser(userRepo);
        Order o = delivered(orderRepo, user, 6, daysAgo(1));
        loyaltyService.onDelivered(o);

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cycle.lunches").value(6))
            .andExpect(jsonPath("$.cycle.startedAt").exists())
            .andExpect(jsonPath("$.cycle.endsAt").exists())
            .andExpect(jsonPath("$.waiting").value(false))
            .andExpect(jsonPath("$.milestones[0].status").value("EARNED"))
            .andExpect(jsonPath("$.milestones[0].expiresAt").exists())
            .andExpect(jsonPath("$.milestones[1].status").value("LOCKED"))
            .andExpect(jsonPath("$.milestones[1].expiresAt").value((Object) null))
            .andExpect(jsonPath("$.pendingDessert.rewardId").exists())
            .andExpect(jsonPath("$.pendingDessert.expiresAt").exists());
    }

    @Test
    @DisplayName("the 18th-lunch prize shows as EARNED with no own expiry (it lives and dies with the wallet balance)")
    void lunchMilestoneEarned() throws Exception {
        User user = newUser(userRepo);
        loyaltyService.onDelivered(delivered(orderRepo, user, 18, daysAgo(1)));

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(jsonPath("$.milestones[1].kind").value("LUNCH"))
            .andExpect(jsonPath("$.milestones[1].status").value("EARNED"))
            .andExpect(jsonPath("$.milestones[1].expiresAt").value((Object) null));
    }

    @Test
    @DisplayName("an unused dessert past its 7 days is EXPIRED and no longer pending")
    void expiredDessert() throws Exception {
        User user = newUser(userRepo);
        // Cycle began 20 days ago; the dessert was earned 10 days ago (7 days of validity ran out).
        loyaltyService.onDelivered(delivered(orderRepo, user, 1, daysAgo(20)));
        Order sixth = delivered(orderRepo, user, 5, daysAgo(10));
        loyaltyService.onDelivered(sixth);

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(jsonPath("$.milestones[0].status").value("EXPIRED"))
            .andExpect(jsonPath("$.pendingDessert").value((Object) null));
    }

    @Test
    @DisplayName("a used dessert is USED")
    void usedDessert() throws Exception {
        User user = newUser(userRepo);
        loyaltyService.onDelivered(delivered(orderRepo, user, 6, daysAgo(1)));
        LoyaltyReward dessert = rewardRepo.findByUserIdOrderByMilestoneAsc(user.getId()).get(0);
        dessert.setUsedAt(Instant.now());
        rewardRepo.saveAndFlush(dessert);

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(jsonPath("$.milestones[0].status").value("USED"))
            .andExpect(jsonPath("$.pendingDessert").value((Object) null));
    }

    @Test
    @DisplayName("when the last cycle already ended the customer is waiting for the next lunch")
    void waitingBetweenCycles() throws Exception {
        User user = newUser(userRepo);
        // Pre-existing history, no loyalty rows yet: the endpoint rebuilds it lazily.
        delivered(orderRepo, user, 7, daysAgo(120));

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.cycle").value((Object) null))
            .andExpect(jsonPath("$.waiting").value(true))
            .andExpect(jsonPath("$.milestones[0].status").value("LOCKED"));
    }

    @Test
    @DisplayName("a company user gets enabled=false")
    void companyUser() throws Exception {
        Category category = categoryRepo.save(Category.builder()
            .nombre("Cat-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
        Company company = companyRepo.save(Company.builder()
            .nombre("Empresa-" + System.nanoTime())
            .cuit(String.valueOf(20000000000L + (System.nanoTime() % 9000000000L)))
            .calle("Calle Falsa").altura("123").horaEntrega(LocalTime.of(13, 0))
            .categoriaDefault(category).enabled(true).build());
        User user = userRepo.save(User.builder()
            .email("loyalty-b2b-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x").role(Role.EMPLOYEE).active(true).company(company).build());

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.milestones").doesNotExist());
    }
}
