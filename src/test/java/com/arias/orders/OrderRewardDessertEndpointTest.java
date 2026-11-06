package com.arias.orders;

import com.arias.auth.JwtService;
import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.desserts.Dessert;
import com.arias.catalog.desserts.DessertRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.loyalty.LoyaltyCycleRepository;
import com.arias.loyalty.LoyaltyRewardRepository;
import com.arias.loyalty.LoyaltyTestSupport;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JSON contract of the dessert redemption: request field, customer order DTOs and the admin kitchen board. */
@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true", "arias.loyalty.enabled=true"})
@Transactional
@Import(OrderRewardDessertEndpointTest.FixedClockConfig.class)
class OrderRewardDessertEndpointTest {

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:40:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired private WebApplicationContext webContext;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private DessertRepository dessertRepo;
    @Autowired private CreditWalletRepository walletRepo;
    @Autowired private LoyaltyCycleRepository cycleRepo;
    @Autowired private LoyaltyRewardRepository rewardRepo;

    private MockMvc mvc;
    private User customer;
    private String auth;
    private Dish dish;
    private Dessert flan;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        customer = customerUser("ep", 1166770100L, "Coty");
        auth = bearer(customer);
        Category category = categoryRepo.save(Category.builder().nombre("Cat-" + System.nanoTime())
            .ordenDisplay(0).enabled(true).creditCost(1).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder().nombre("sec-" + System.nanoTime())
            .ordenDisplay(0).enabled(true).build());
        dish = dishRepo.save(Dish.builder().nombre("Milanesa").category(category).menuSection(section)
            .enabled(true).especial(false).stockDiarioDefault(9).stockActual(9).build());
        flan = LoyaltyTestSupport.dessert(dessertRepo, "Flan mixto", 4);
        LoyaltyTestSupport.dessertReward(cycleRepo, rewardRepo, customer,
            FIXED_NOW.minus(Duration.ofDays(1)), FIXED_NOW.plus(Duration.ofDays(6)));
    }

    private User customerUser(String prefix, long phoneBase, String nickname) {
        User u = userRepo.save(User.builder().email(prefix + "-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE).active(true).emailVerifiedAt(Instant.now())
            .phone("+549" + (phoneBase + System.nanoTime() % 1_000_000)).nickname(nickname).build());
        walletRepo.saveAndFlush(CreditWallet.builder().userId(u.getId()).available(5).committed(0).build());
        return u;
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private String body(String extra) {
        return "{\"items\":[{\"dishId\":" + dish.getId() + "}],\"pickupAt\":\""
            + FIXED_NOW.plus(90, ChronoUnit.MINUTES) + "\"" + extra + "}";
    }

    @Test
    @DisplayName("POST /api/v2/orders with rewardDessertId returns rewardItems [{dessertId, nombre}]")
    void placeJson() throws Exception {
        mvc.perform(post("/api/v2/orders").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(body(",\"rewardDessertId\":" + flan.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.creditTotal").value(1))
            .andExpect(jsonPath("$.rewardItems.length()").value(1))
            .andExpect(jsonPath("$.rewardItems[0].dessertId").value(flan.getId()))
            .andExpect(jsonPath("$.rewardItems[0].nombre").value("Flan mixto"));
    }

    @Test
    @DisplayName("POST /api/v2/orders without a dessert returns rewardItems []")
    void placeJsonWithoutDessert() throws Exception {
        mvc.perform(post("/api/v2/orders").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(body("")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rewardItems.length()").value(0));
    }

    @Test
    @DisplayName("errors surface as 404 dessert-not-found and 409 dessert-out-of-stock / no-pending-dessert")
    void errorCodes() throws Exception {
        mvc.perform(post("/api/v2/orders").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(body(",\"rewardDessertId\":999999999")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("dessert-not-found"));

        flan.setStockActual(0);
        dessertRepo.saveAndFlush(flan);
        mvc.perform(post("/api/v2/orders").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(body(",\"rewardDessertId\":" + flan.getId())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("dessert-out-of-stock"));

        User other = customerUser("ep2", 1166880100L, "Otro");
        mvc.perform(post("/api/v2/orders").header(HttpHeaders.AUTHORIZATION, bearer(other))
                .contentType(MediaType.APPLICATION_JSON).content(body(",\"rewardDessertId\":" + flan.getId())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("no-pending-dessert"));
    }

    @Test
    @DisplayName("GET /api/v2/orders lists rewardItems and the loyalty endpoint stops offering the dessert")
    void listAndLoyalty() throws Exception {
        mvc.perform(post("/api/v2/orders").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(body(",\"rewardDessertId\":" + flan.getId())))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v2/orders").header(HttpHeaders.AUTHORIZATION, auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].rewardItems[0].nombre").value("Flan mixto"));
        mvc.perform(get("/api/v1/loyalty").header(HttpHeaders.AUTHORIZATION, auth))
            .andExpect(jsonPath("$.pendingDessert").value((Object) null))
            .andExpect(jsonPath("$.milestones[0].status").value("USED"));
    }

    @Test
    @DisplayName("the admin kitchen board (by-pickup) shows rewardItems on each order, also on cancelled ones")
    void adminBoard() throws Exception {
        User admin = userRepo.save(User.builder().email("adm-" + System.nanoTime() + "@test.arias.com")
            .role(Role.SUPER_ADMIN).active(true).build());
        String placed = mvc.perform(post("/api/v2/orders").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(body(",\"rewardDessertId\":" + flan.getId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Number orderId = com.jayway.jsonpath.JsonPath.read(placed, "$.id");

        mvc.perform(get("/api/v1/admin/orders/by-pickup").param("fecha", "2026-03-10")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].orders[0].rewardItems[0].nombre").value("Flan mixto"))
            .andExpect(jsonPath("$[0].orders[0].rewardItems[0].dessertId").value(flan.getId()));

        mvc.perform(delete("/api/v2/orders/" + orderId.longValue()).header(HttpHeaders.AUTHORIZATION, auth))
            .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/v1/admin/orders/by-pickup/cancelled").param("fecha", "2026-03-10")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].rewardItems[0].nombre").value("Flan mixto"));
    }

    @Test
    @DisplayName("the by-pickup Excel export lists the gift dessert as an extra row outside the plate total")
    void excelExport() throws Exception {
        User admin = userRepo.save(User.builder().email("adm2-" + System.nanoTime() + "@test.arias.com")
            .role(Role.SUPER_ADMIN).active(true).build());
        mvc.perform(post("/api/v2/orders").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(body(",\"rewardDessertId\":" + flan.getId())))
            .andExpect(status().isOk());

        byte[] xlsx = mvc.perform(get("/api/v1/admin/orders/export/by-pickup").param("fecha", "2026-03-10")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            var cells = new java.util.ArrayList<String>();
            for (var row : sheet) {
                for (var cell : row) {
                    cells.add(cell.toString());
                }
            }
            org.assertj.core.api.Assertions.assertThat(cells).contains("POSTRE DE REGALO: Flan mixto", "Milanesa");
            // TOTAL counts plates only: the dessert row is not one.
            int totalRow = -1;
            for (var row : sheet) {
                if (row.getCell(0) != null && "TOTAL:".equals(row.getCell(0).toString())) {
                    totalRow = row.getRowNum();
                }
            }
            org.assertj.core.api.Assertions.assertThat(sheet.getRow(totalRow).getCell(1).getNumericCellValue())
                .isEqualTo(1.0);
        }
    }
}
