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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin side: reprint, printers, agent status and the print status on the kitchen board. */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(AdminPrintControllerTest.FixedClockConfig.class)
class AdminPrintControllerTest {

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
    @Autowired private PrinterRepository printerRepo;
    @Autowired private PrintAgentStatusRepository agentStatusRepo;

    private MockMvc mvc;
    private Category category;
    private MenuSection section;
    private User customer;
    private String admin;

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
    }

    private Order order(OrderEstado estado, DishStation... stations) {
        Instant pickupAt = Instant.parse("2026-03-10T16:30:00Z");
        Order o = Order.builder().user(customer).fecha(LocalDate.of(2026, 3, 10)).pickupAt(pickupAt)
            .estado(estado).creditTotal(stations.length).build();
        for (DishStation s : stations) {
            Dish d = dishRepo.save(Dish.builder().nombre("Plato-" + s + System.nanoTime()).category(category)
                .menuSection(section).station(s).enabled(true).especial(false)
                .stockDiarioDefault(5).stockActual(5).build());
            o.addItem(OrderItem.builder().dish(d).category(category).dishNombre(d.getNombre())
                .dishCategoria(category.getNombre()).creditCost(1).build());
        }
        return orderRepo.save(o);
    }

    private PrintJob job(Order o, DishStation station, PrintJobStatus status, Instant printedAt) {
        return jobRepo.saveAndFlush(PrintJob.builder().order(o).station(station).host("192.168.0.121")
            .port(9100).data(new byte[] {0x1B, 0x40}).status(status).availableAt(NOW)
            .printedAt(printedAt).build());
    }

    // -- reprint ------------------------------------------------------------

    @Test
    @DisplayName("reprint of a COMANDADO order enqueues the routed jobs, available immediately")
    void reprintComandado() throws Exception {
        Order o = order(OrderEstado.COMANDADO, DishStation.COCINA, DishStation.PARRILLA);

        mvc.perform(post("/api/v1/admin/orders/" + o.getId() + "/print").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        List<PrintJob> jobs = jobRepo.findByOrderId(o.getId());
        assertThat(jobs).extracting(PrintJob::getStation)
            .containsExactlyInAnyOrder(DishStation.COCINA, DishStation.PARRILLA);
        assertThat(jobs).allSatisfy(j -> {
            assertThat(j.getAvailableAt()).isEqualTo(NOW);
            assertThat(j.getStatus()).isEqualTo(PrintJobStatus.PENDING);
        });
    }

    @Test
    @DisplayName("reprint while jobs are unfinished returns the existing jobs and enqueues nothing")
    void reprintIsIdempotentWhileUnfinished() throws Exception {
        Order o = order(OrderEstado.COMANDADO, DishStation.COCINA, DishStation.PARRILLA);
        PrintJob pending = job(o, DishStation.COCINA, PrintJobStatus.PENDING, null);
        PrintJob claimed = job(o, DishStation.PARRILLA, PrintJobStatus.CLAIMED, null);

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/admin/orders/" + o.getId() + "/print").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(pending.getId()))
                .andExpect(jsonPath("$[1].id").value(claimed.getId()));
        }

        assertThat(jobRepo.findByOrderId(o.getId())).hasSize(2);
    }

    @Test
    @DisplayName("reprint enqueues again when every job is finished or cancelled")
    void reprintAfterFinished() throws Exception {
        Order o = order(OrderEstado.COMANDADO, DishStation.COCINA);
        job(o, DishStation.COCINA, PrintJobStatus.PRINTED, NOW);
        job(o, DishStation.COCINA, PrintJobStatus.FAILED, null);
        job(o, DishStation.COCINA, PrintJobStatus.CANCELLED, null);

        mvc.perform(post("/api/v1/admin/orders/" + o.getId() + "/print").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        assertThat(jobRepo.findByOrderId(o.getId())).hasSize(4);
    }

    @Test
    @DisplayName("reprint of an ENTREGADO order is allowed")
    void reprintEntregado() throws Exception {
        Order o = order(OrderEstado.ENTREGADO, DishStation.COCINA);

        mvc.perform(post("/api/v1/admin/orders/" + o.getId() + "/print").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("reprint of an order that is not COMANDADO or ENTREGADO is 409")
    void reprintWrongState() throws Exception {
        for (OrderEstado estado : new OrderEstado[] {OrderEstado.CONFIRMADO, OrderEstado.PENDIENTE,
            OrderEstado.CANCELADO}) {
            Order o = order(estado, DishStation.COCINA);

            mvc.perform(post("/api/v1/admin/orders/" + o.getId() + "/print").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());
            assertThat(jobRepo.findByOrderId(o.getId())).isEmpty();
        }
    }

    @Test
    @DisplayName("reprint of an unknown order is 404")
    void reprintUnknown() throws Exception {
        mvc.perform(post("/api/v1/admin/orders/999999999/print").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("reprint is SUPER_ADMIN only")
    void reprintForbiddenForEmployees() throws Exception {
        Order o = order(OrderEstado.COMANDADO, DishStation.COCINA);
        String employee = "Bearer " + jwtService.issueAccessToken(customer);

        mvc.perform(post("/api/v1/admin/orders/" + o.getId() + "/print").header(HttpHeaders.AUTHORIZATION, employee))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/orders/" + o.getId() + "/print")).andExpect(status().isUnauthorized());
    }

    // -- printers -----------------------------------------------------------

    @Test
    @DisplayName("lists the printers with host and port")
    void listPrinters() throws Exception {
        mvc.perform(get("/api/v1/admin/print/printers").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].station").value("COCINA"))
            .andExpect(jsonPath("$[0].host").value("192.168.0.121"))
            .andExpect(jsonPath("$[0].port").value(9100))
            .andExpect(jsonPath("$[1].station").value("PARRILLA"));
    }

    @Test
    @DisplayName("updates a printer")
    void updatePrinter() throws Exception {
        mvc.perform(put("/api/v1/admin/print/printers/PARRILLA").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"host\":\"192.168.0.130\",\"port\":9101}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.host").value("192.168.0.130"))
            .andExpect(jsonPath("$.port").value(9101));

        Printer p = printerRepo.findById(DishStation.PARRILLA).orElseThrow();
        assertThat(p.getHost()).isEqualTo("192.168.0.130");
    }

    @Test
    @DisplayName("rejects a public host or a bad port with 400")
    void updatePrinterInvalid() throws Exception {
        mvc.perform(put("/api/v1/admin/print/printers/COCINA").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"host\":\"8.8.8.8\",\"port\":9100}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/print/printers/COCINA").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"host\":\"192.168.0.121\",\"port\":70000}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/print/printers/COCINA").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"port\":9100}"))
            .andExpect(status().isBadRequest());

        assertThat(printerRepo.findById(DishStation.COCINA).orElseThrow().getHost()).isEqualTo("192.168.0.121");
    }

    // -- agent status -------------------------------------------------------

    @Test
    @DisplayName("the agent is offline when its last heartbeat is older than 30 s")
    void agentOfflineWhenStale() throws Exception {
        agentStatusRepo.touch(NOW.minusSeconds(31));

        mvc.perform(get("/api/v1/admin/print/agent").header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(jsonPath("$.online").value(false))
            .andExpect(jsonPath("$.lastSeenAt").value("2026-03-10T12:59:29Z"));
    }

    // -- print status on the board DTO --------------------------------------

    private String boardStatusOf(Order o) throws Exception {
        String body = mvc.perform(get("/api/v1/admin/orders/by-pickup").param("fecha", "2026-03-10")
                .header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        for (com.fasterxml.jackson.databind.JsonNode group : root) {
            for (com.fasterxml.jackson.databind.JsonNode ord : group.get("orders")) {
                if (ord.get("id").asLong() == o.getId()) {
                    return ord.get("printStatus").asText() + "|" + ord.get("printedAt").asText();
                }
            }
        }
        throw new AssertionError("order not on the board");
    }

    @Test
    @DisplayName("board DTO: NONE when the order has no jobs")
    void boardNone() throws Exception {
        Order o = order(OrderEstado.CONFIRMADO, DishStation.COCINA);

        assertThat(boardStatusOf(o)).isEqualTo("NONE|null");
    }

    @Test
    @DisplayName("board DTO: QUEUED while any job is pending or claimed")
    void boardQueued() throws Exception {
        Order o = order(OrderEstado.COMANDADO, DishStation.COCINA, DishStation.PARRILLA);
        job(o, DishStation.COCINA, PrintJobStatus.PRINTED, NOW);
        job(o, DishStation.PARRILLA, PrintJobStatus.CLAIMED, null);

        assertThat(boardStatusOf(o)).isEqualTo("QUEUED|null");
    }

    @Test
    @DisplayName("board DTO: PRINTED with printedAt when every latest job is printed")
    void boardPrinted() throws Exception {
        Order o = order(OrderEstado.COMANDADO, DishStation.COCINA, DishStation.PARRILLA);
        job(o, DishStation.COCINA, PrintJobStatus.PRINTED, NOW.minusSeconds(20));
        job(o, DishStation.PARRILLA, PrintJobStatus.PRINTED, NOW.minusSeconds(5));

        assertThat(boardStatusOf(o)).isEqualTo("PRINTED|2026-03-10T12:59:55Z");
    }

    @Test
    @DisplayName("board DTO: FAILED when a latest job failed")
    void boardFailed() throws Exception {
        Order o = order(OrderEstado.COMANDADO, DishStation.COCINA, DishStation.PARRILLA);
        job(o, DishStation.COCINA, PrintJobStatus.PRINTED, NOW);
        job(o, DishStation.PARRILLA, PrintJobStatus.FAILED, null);

        assertThat(boardStatusOf(o)).isEqualTo("FAILED|null");
    }

    @Test
    @DisplayName("board DTO: only the latest job per station counts, and cancelled jobs are ignored")
    void boardLatestJobPerStation() throws Exception {
        Order o = order(OrderEstado.COMANDADO, DishStation.COCINA);
        job(o, DishStation.COCINA, PrintJobStatus.FAILED, null);
        job(o, DishStation.COCINA, PrintJobStatus.PRINTED, NOW);
        job(o, DishStation.COCINA, PrintJobStatus.CANCELLED, null);

        assertThat(boardStatusOf(o)).isEqualTo("PRINTED|2026-03-10T13:00:00Z");
    }
}
