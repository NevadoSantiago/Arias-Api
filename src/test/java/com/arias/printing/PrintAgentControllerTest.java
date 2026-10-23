package com.arias.printing;

import com.arias.auth.JwtService;
import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.dishes.DishStation;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
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
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agent API under /api/v1/print-agent/**: key auth, claim, ack, heartbeat. */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(PrintAgentControllerTest.FixedClockConfig.class)
class PrintAgentControllerTest {

    static final Instant NOW = Instant.parse("2026-03-10T13:00:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    static final String KEY = "test-print-agent-key";
    static final String HEADER = "X-Print-Agent-Key";

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
    @Autowired private PrintAgentStatusRepository statusRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private PrintJobRepository jobRepo;
    @Autowired private jakarta.persistence.EntityManager em;

    private MockMvc mvc;
    private Order order;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        Dish dish = dishRepo.save(Dish.builder().nombre("Fideos").category(category).menuSection(section)
            .enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
        User user = userRepo.save(User.builder()
            .email("agent-" + System.nanoTime() + "@test.arias.com").role(Role.EMPLOYEE)
            .nickname("Coty").active(true).build());
        Instant pickupAt = NOW.plusSeconds(3600);
        order = Order.builder().user(user).fecha(LocalDate.ofInstant(pickupAt, ZONE)).pickupAt(pickupAt)
            .estado(OrderEstado.COMANDADO).creditTotal(1).build();
        order.addItem(OrderItem.builder().dish(dish).category(category).dishNombre("Fideos")
            .dishCategoria(category.getNombre()).creditCost(1).build());
        order = orderRepo.save(order);
    }

    private static final String TOKEN = "11111111-1111-1111-1111-111111111111";

    private PrintJob job(PrintJobStatus status, Instant availableAt, Instant claimedAt, int attempts) {
        return jobRepo.saveAndFlush(PrintJob.builder().order(order).station(DishStation.COCINA)
            .host("192.168.0.121").port(9100).data(new byte[] {0x1B, 0x40, 'h', 'i', 0x1B, 0x40})
            .status(status).availableAt(availableAt).claimedAt(claimedAt).attempts(attempts)
            .claimToken(status == PrintJobStatus.CLAIMED ? TOKEN : null).build());
    }

    private PrintJob reload(PrintJob j) {
        em.flush();
        em.clear();
        return jobRepo.findById(j.getId()).orElseThrow();
    }

    private String adminBearer() {
        User admin = userRepo.save(User.builder().email("adm-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x").role(Role.SUPER_ADMIN).active(true).build());
        return "Bearer " + jwtService.issueAccessToken(admin);
    }

    // -- authentication -----------------------------------------------------

    @Test
    @DisplayName("claim without the key is 401")
    void claimWithoutKey() throws Exception {
        mvc.perform(post("/api/v1/print-agent/jobs/claim")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("claim with a wrong key is 401")
    void claimWithWrongKey() throws Exception {
        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, "nope"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ack without the key or with a wrong key is 401")
    void ackWithoutKey() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW, NOW, 0);
        String body = "{\"status\":\"PRINTED\",\"claimToken\":\"" + TOKEN + "\"}";

        mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, "nope")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an admin JWT does not open the agent endpoints")
    void adminJwtIsNotAnAgentKey() throws Exception {
        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HttpHeaders.AUTHORIZATION, adminBearer()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the agent key does not open admin endpoints")
    void agentKeyIsNotAnAdminCredential() throws Exception {
        mvc.perform(get("/api/v1/admin/print/agent").header(HEADER, KEY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the JWT chain still works: admin JWT is 200, no token is 401")
    void jwtChainUnaffected() throws Exception {
        mvc.perform(get("/api/v1/admin/print/agent").header(HttpHeaders.AUTHORIZATION, adminBearer()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/print/agent")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/dishes/admin").header(HttpHeaders.AUTHORIZATION, adminBearer()))
            .andExpect(status().isOk());
    }

    // -- claim --------------------------------------------------------------

    @Test
    @DisplayName("claim returns available jobs as id, host, port and base64 data, and marks them CLAIMED")
    void claimReturnsAvailableJobs() throws Exception {
        PrintJob j = job(PrintJobStatus.PENDING, NOW.minusSeconds(1), null, 0);

        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(j.getId()))
            .andExpect(jsonPath("$[0].host").value("192.168.0.121"))
            .andExpect(jsonPath("$[0].port").value(9100))
            .andExpect(jsonPath("$[0].claimToken").isNotEmpty())
            .andExpect(jsonPath("$[0].dataBase64")
                .value(Base64.getEncoder().encodeToString(j.getData())));

        PrintJob after = reload(j);
        assertThat(after.getStatus()).isEqualTo(PrintJobStatus.CLAIMED);
        assertThat(after.getClaimedAt()).isEqualTo(NOW);
        assertThat(after.getClaimToken()).isNotBlank();
    }

    @Test
    @DisplayName("jobs scheduled in the future (the 10 s delay) are not claimable yet")
    void futureJobsNotClaimed() throws Exception {
        PrintJob j = job(PrintJobStatus.PENDING, NOW.plusSeconds(10), null, 0);

        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        assertThat(reload(j).getStatus()).isEqualTo(PrintJobStatus.PENDING);
    }

    @Test
    @DisplayName("a second claim does not return jobs already claimed")
    void secondClaimGetsNothing() throws Exception {
        job(PrintJobStatus.PENDING, NOW.minusSeconds(1), null, 0);

        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY))
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("a claim older than 60 s is re-offered; a recent one is not")
    void staleClaimIsReoffered() throws Exception {
        PrintJob stale = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(120), NOW.minusSeconds(61), 0);
        PrintJob recent = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(120), NOW.minusSeconds(30), 0);

        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(stale.getId()));

        assertThat(reload(stale).getClaimedAt()).isEqualTo(NOW);
        assertThat(reload(recent).getClaimedAt()).isEqualTo(NOW.minusSeconds(30));
    }

    @Test
    @DisplayName("a job that was re-offered too many times without any ack ends FAILED instead of printing forever")
    void staleClaimsEventuallyFail() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(120), NOW.minusSeconds(61), 4);

        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY))
            .andExpect(jsonPath("$.length()").value(0));

        PrintJob after = reload(j);
        assertThat(after.getStatus()).isEqualTo(PrintJobStatus.FAILED);
        assertThat(after.getLastError()).isNotBlank();
    }

    @Test
    @DisplayName("claim returns at most 10 jobs")
    void claimLimit() throws Exception {
        for (int i = 0; i < 12; i++) {
            job(PrintJobStatus.PENDING, NOW.minusSeconds(1), null, 0);
        }

        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY))
            .andExpect(jsonPath("$.length()").value(10));
        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY))
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("cancelled jobs are never claimed")
    void cancelledNotClaimed() throws Exception {
        job(PrintJobStatus.CANCELLED, NOW.minusSeconds(1), null, 0);

        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("every claim records the agent heartbeat, shown by the admin agent endpoint")
    void heartbeat() throws Exception {
        // Start from "never seen", whatever an earlier committed test left behind.
        statusRepo.touch(null);
        String admin = adminBearer();
        mvc.perform(get("/api/v1/admin/print/agent").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(jsonPath("$.online").value(false))
            .andExpect(jsonPath("$.lastSeenAt").doesNotExist());

        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/print/agent").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(jsonPath("$.online").value(true))
            .andExpect(jsonPath("$.lastSeenAt").value("2026-03-10T13:00:00Z"));
    }

    // -- ack ----------------------------------------------------------------

    private String ack(PrintJob j, String body) throws Exception {
        return mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("ack PRINTED sets printedAt")
    void ackPrinted() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(5), NOW.minusSeconds(2), 0);

        mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PRINTED\",\"claimToken\":\"" + TOKEN + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PRINTED"));

        PrintJob after = reload(j);
        assertThat(after.getStatus()).isEqualTo(PrintJobStatus.PRINTED);
        assertThat(after.getPrintedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("ack PRINTED twice is idempotent")
    void ackPrintedTwice() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(5), NOW.minusSeconds(2), 0);

        ack(j, "{\"status\":\"PRINTED\",\"claimToken\":\"" + TOKEN + "\"}");
        mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PRINTED\",\"claimToken\":\"" + TOKEN + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ack FAILED increments attempts and reschedules the job as PENDING in 15 s")
    void ackFailedReschedules() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(5), NOW.minusSeconds(2), 0);

        mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FAILED\",\"error\":\"timeout\",\"claimToken\":\"" + TOKEN + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));

        PrintJob after = reload(j);
        assertThat(after.getStatus()).isEqualTo(PrintJobStatus.PENDING);
        assertThat(after.getAttempts()).isEqualTo(1);
        assertThat(after.getAvailableAt()).isEqualTo(NOW.plusSeconds(15));
        assertThat(after.getLastError()).isEqualTo("timeout");
        assertThat(after.getClaimedAt()).isNull();
    }

    @Test
    @DisplayName("the fifth failure leaves the job FAILED with the last error, truncated")
    void ackFailedFinal() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(5), NOW.minusSeconds(2), 4);
        String longError = "e".repeat(2000);

        mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FAILED\",\"error\":\"" + longError + "\",\"claimToken\":\"" + TOKEN + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"));

        PrintJob after = reload(j);
        assertThat(after.getStatus()).isEqualTo(PrintJobStatus.FAILED);
        assertThat(after.getAttempts()).isEqualTo(5);
        assertThat(after.getLastError()).hasSize(500);
    }

    @Test
    @DisplayName("ack of an unknown job is 404")
    void ackUnknown() throws Exception {
        mvc.perform(post("/api/v1/print-agent/jobs/999999999/ack").header(HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PRINTED\",\"claimToken\":\"" + TOKEN + "\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ack of a job that is not claimed is 409")
    void ackNotClaimed() throws Exception {
        PrintJob pending = job(PrintJobStatus.PENDING, NOW, null, 0);
        PrintJob cancelled = job(PrintJobStatus.CANCELLED, NOW, null, 0);

        for (PrintJob j : new PrintJob[] {pending, cancelled}) {
            mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, KEY)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PRINTED\",\"claimToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isConflict());
        }
    }

    @Test
    @DisplayName("ack with a missing or unknown status is 400")
    void ackInvalidBody() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW, NOW, 0);

        for (String body : new String[] {"{}", "{\"status\":\"PENDING\"}", "{\"status\":\"NOPE\"}"}) {
            mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, KEY)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("every claim, including a re-offer, issues a fresh claim token")
    void reofferRegeneratesToken() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(120), NOW.minusSeconds(61), 0);

        mvc.perform(post("/api/v1/print-agent/jobs/claim").header(HEADER, KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].claimToken").value(org.hamcrest.Matchers.not(TOKEN)));

        assertThat(reload(j).getClaimToken()).isNotBlank().isNotEqualTo(TOKEN);
    }

    @Test
    @DisplayName("a late ack carrying a stale claim token is 409 and leaves the job untouched")
    void staleTokenAck() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(5), NOW.minusSeconds(2), 0);

        for (String status : new String[] {"PRINTED", "FAILED"}) {
            mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"" + status + "\",\"error\":\"x\",\"claimToken\":\"stale\"}"))
                .andExpect(status().isConflict());
        }

        PrintJob after = reload(j);
        assertThat(after.getStatus()).isEqualTo(PrintJobStatus.CLAIMED);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getPrintedAt()).isNull();
    }

    @Test
    @DisplayName("ack without a claim token is 400")
    void ackWithoutToken() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(5), NOW.minusSeconds(2), 0);

        mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PRINTED\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a PRINTED ack with a stale token on an already printed job is 409")
    void printedStaleTokenAck() throws Exception {
        PrintJob j = job(PrintJobStatus.CLAIMED, NOW.minusSeconds(5), NOW.minusSeconds(2), 0);
        ack(j, "{\"status\":\"PRINTED\",\"claimToken\":\"" + TOKEN + "\"}");

        mvc.perform(post("/api/v1/print-agent/jobs/" + j.getId() + "/ack").header(HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PRINTED\",\"claimToken\":\"stale\"}"))
            .andExpect(status().isConflict());
    }
}
