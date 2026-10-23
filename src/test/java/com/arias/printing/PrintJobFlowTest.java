package com.arias.printing;

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
import com.arias.orders.OrderKitchenService;
import com.arias.orders.OrderRepository;
import com.arias.orders.OrderService;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Enqueue on comandar (routing + delay), cancel on undo, and the seeded printers. */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(PrintJobFlowTest.FixedClockConfig.class)
class PrintJobFlowTest {

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:00:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired private OrderKitchenService kitchen;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private PrintJobRepository jobRepo;
    @Autowired private PrinterRepository printerRepo;
    @Autowired private jakarta.persistence.EntityManager em;

    private Category category;
    private MenuSection section;
    private User user;

    private Dish dish(String name, DishStation station) {
        if (category == null) {
            category = categoryRepo.save(Category.builder()
                .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
            section = menuSectionRepo.save(MenuSection.builder()
                .nombre("seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
            user = userRepo.save(User.builder()
                .email("p-" + System.nanoTime() + "@test.arias.com").role(Role.EMPLOYEE)
                .nickname("Coty").active(true).build());
        }
        return dishRepo.save(Dish.builder().nombre(name).category(category).menuSection(section)
            .station(station).enabled(true).especial(false).stockDiarioDefault(50).stockActual(50).build());
    }

    private Order confirmedOrder(Dish... dishes) {
        Instant pickupAt = FIXED_NOW.plusSeconds(3600);
        Order order = Order.builder()
            .user(user).fecha(LocalDate.ofInstant(pickupAt, ZONE)).pickupAt(pickupAt)
            .estado(OrderEstado.CONFIRMADO).creditTotal(dishes.length).build();
        for (Dish d : dishes) {
            order.addItem(OrderItem.builder().dish(d).category(category).dishNombre(d.getNombre())
                .dishCategoria(category.getNombre()).creditCost(1).build());
        }
        return orderRepo.save(order);
    }

    @Test
    @DisplayName("the migration seeds the printers: COCINA 192.168.0.121:9100 and PARRILLA 192.168.0.120:9100")
    void seededPrinters() {
        Printer cocina = printerRepo.findById(DishStation.COCINA).orElseThrow();
        Printer parrilla = printerRepo.findById(DishStation.PARRILLA).orElseThrow();

        assertThat(cocina.getHost()).isEqualTo("192.168.0.121");
        assertThat(cocina.getPort()).isEqualTo(9100);
        assertThat(parrilla.getHost()).isEqualTo("192.168.0.120");
        assertThat(parrilla.getPort()).isEqualTo(9100);
    }

    @Test
    @DisplayName("an order with only COCINA dishes enqueues exactly one COCINA job")
    void cocinaOnly() {
        Order o = confirmedOrder(dish("Fideos", DishStation.COCINA));

        kitchen.markComandado(List.of(o.getId()));

        List<PrintJob> jobs = jobRepo.findByOrderId(o.getId());
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getStation()).isEqualTo(DishStation.COCINA);
    }

    @Test
    @DisplayName("an order with a PARRILLA dish enqueues a COCINA job and a PARRILLA job, both with the full order")
    void cocinaAndParrilla() {
        Order o = confirmedOrder(dish("Fideos", DishStation.COCINA), dish("Bife", DishStation.PARRILLA));

        kitchen.markComandado(List.of(o.getId()));

        List<PrintJob> jobs = jobRepo.findByOrderId(o.getId());
        assertThat(jobs).extracting(PrintJob::getStation)
            .containsExactlyInAnyOrder(DishStation.COCINA, DishStation.PARRILLA);
        for (PrintJob j : jobs) {
            String text = new String(j.getData(), java.nio.charset.Charset.forName("IBM850"));
            assertThat(text).contains("Fideos").contains("Bife").contains(j.getStation().name());
        }
    }

    @Test
    @DisplayName("an order with only PARRILLA dishes still enqueues COCINA (always) plus PARRILLA")
    void parrillaOnlyStillPrintsCocina() {
        Order o = confirmedOrder(dish("Bife", DishStation.PARRILLA));

        kitchen.markComandado(List.of(o.getId()));

        assertThat(jobRepo.findByOrderId(o.getId())).extracting(PrintJob::getStation)
            .containsExactlyInAnyOrder(DishStation.COCINA, DishStation.PARRILLA);
    }

    @Test
    @DisplayName("jobs become available 10 seconds after comandar and store the printer host and port")
    void delayAndTarget() {
        Order o = confirmedOrder(dish("Fideos", DishStation.COCINA));

        kitchen.markComandado(List.of(o.getId()));

        PrintJob job = jobRepo.findByOrderId(o.getId()).get(0);
        assertThat(job.getAvailableAt()).isEqualTo(FIXED_NOW.plusSeconds(10));
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.PENDING);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getHost()).isEqualTo("192.168.0.121");
        assertThat(job.getPort()).isEqualTo(9100);
        assertThat(job.getData()[0]).isEqualTo((byte) 0x1B);
        assertThat(job.getData()[1]).isEqualTo((byte) 0x40);
    }

    @Test
    @DisplayName("the job keeps the host and port the printer had when it was enqueued")
    void jobSnapshotsPrinterTarget() {
        Printer cocina = printerRepo.findById(DishStation.COCINA).orElseThrow();
        cocina.setHost("10.1.2.3");
        cocina.setPort(9101);
        printerRepo.saveAndFlush(cocina);
        Order o = confirmedOrder(dish("Fideos", DishStation.COCINA));

        kitchen.markComandado(List.of(o.getId()));

        PrintJob job = jobRepo.findByOrderId(o.getId()).get(0);
        assertThat(job.getHost()).isEqualTo("10.1.2.3");
        assertThat(job.getPort()).isEqualTo(9101);
    }

    @Test
    @DisplayName("batch comandar enqueues jobs for every order")
    void batchEnqueuesPerOrder() {
        Dish fideos = dish("Fideos", DishStation.COCINA);
        Order a = confirmedOrder(fideos);
        Order b = confirmedOrder(fideos);

        kitchen.markComandado(List.of(a.getId(), b.getId()));

        assertThat(jobRepo.findByOrderId(a.getId())).hasSize(1);
        assertThat(jobRepo.findByOrderId(b.getId())).hasSize(1);
    }

    @Test
    @DisplayName("undo cancels the order's jobs that are still PENDING")
    void undoCancelsPending() {
        Order o = confirmedOrder(dish("Fideos", DishStation.COCINA), dish("Bife", DishStation.PARRILLA));
        kitchen.markComandado(List.of(o.getId()));

        Order undone = kitchen.undo(o.getId());

        // The returned order must stay usable by the controller (items still reachable).
        assertThat(undone.getItems()).hasSize(2);
        em.clear();
        assertThat(jobRepo.findByOrderId(o.getId())).extracting(PrintJob::getStatus)
            .containsOnly(PrintJobStatus.CANCELLED);
    }

    @Test
    @DisplayName("undo leaves claimed and printed jobs alone")
    void undoKeepsClaimedAndPrinted() {
        Order o = confirmedOrder(dish("Fideos", DishStation.COCINA), dish("Bife", DishStation.PARRILLA));
        kitchen.markComandado(List.of(o.getId()));
        List<PrintJob> jobs = jobRepo.findByOrderId(o.getId());
        PrintJob claimed = jobs.stream().filter(j -> j.getStation() == DishStation.COCINA).findFirst().orElseThrow();
        PrintJob printed = jobs.stream().filter(j -> j.getStation() == DishStation.PARRILLA).findFirst().orElseThrow();
        claimed.setStatus(PrintJobStatus.CLAIMED);
        claimed.setClaimedAt(FIXED_NOW);
        printed.setStatus(PrintJobStatus.PRINTED);
        printed.setPrintedAt(FIXED_NOW);
        jobRepo.saveAllAndFlush(jobs);

        kitchen.undo(o.getId());

        em.clear();
        PrintJobStatus claimedAfter = jobRepo.findById(claimed.getId()).orElseThrow().getStatus();
        PrintJobStatus printedAfter = jobRepo.findById(printed.getId()).orElseThrow().getStatus();
        assertThat(claimedAfter).isEqualTo(PrintJobStatus.CLAIMED);
        assertThat(printedAfter).isEqualTo(PrintJobStatus.PRINTED);
    }

    @Test
    @DisplayName("undo of ENTREGADO -> COMANDADO does not cancel the jobs")
    void undoDeliveredKeepsJobs() {
        Order o = confirmedOrder(dish("Fideos", DishStation.COCINA));
        kitchen.markComandado(List.of(o.getId()));
        kitchen.markEntregado(List.of(o.getId()));

        kitchen.undo(o.getId());

        em.clear();
        assertThat(jobRepo.findByOrderId(o.getId())).extracting(PrintJob::getStatus)
            .containsOnly(PrintJobStatus.PENDING);
    }

    @Test
    @DisplayName("the legacy Excel path (markComandadoByCompany) never enqueues jobs")
    void excelPathDoesNotEnqueue() {
        confirmedOrder(dish("Fideos", DishStation.COCINA));
        long before = jobRepo.count();

        orderService.markComandadoByCompany(-1L, LocalDate.ofInstant(FIXED_NOW, ZONE));

        assertThat(jobRepo.count()).isEqualTo(before);
    }
}
