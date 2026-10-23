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
import com.arias.orders.OrderRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two agents claiming at the same time must never get the same job. Needs committed data, so this
 * test is NOT transactional and cleans up after itself.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Import(PrintJobClaimConcurrencyTest.FixedClockConfig.class)
class PrintJobClaimConcurrencyTest {

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

    @Autowired private PrintAgentService agentService;
    @Autowired private PrintJobRepository jobRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private PrintAgentStatusRepository statusRepo;
    @Autowired private TransactionTemplate tx;

    private Long orderId;
    private Long userId;
    private Long dishId;
    private Long categoryId;
    private Long sectionId;

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(s -> {
            if (orderId != null) {
                jobRepo.deleteAll(jobRepo.findByOrderId(orderId));
                orderRepo.deleteById(orderId);
            }
            if (dishId != null) dishRepo.deleteById(dishId);
            if (userId != null) userRepo.deleteById(userId);
            if (categoryId != null) categoryRepo.deleteById(categoryId);
            if (sectionId != null) menuSectionRepo.deleteById(sectionId);
            // The claims committed a heartbeat; clear it so it does not leak into other test classes.
            statusRepo.touch(null);
        });
    }

    @Test
    @DisplayName("concurrent claims never return the same job twice")
    void noDoubleClaim() throws Exception {
        int jobs = 40;
        tx.executeWithoutResult(s -> {
            Category category = categoryRepo.save(Category.builder()
                .nombre("Cat-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
            MenuSection section = menuSectionRepo.save(MenuSection.builder()
                .nombre("sec-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
            Dish dish = dishRepo.save(Dish.builder().nombre("Fideos").category(category).menuSection(section)
                .enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
            User user = userRepo.save(User.builder().email("conc-" + System.nanoTime() + "@test.arias.com")
                .role(Role.EMPLOYEE).nickname("Coty").active(true).build());
            Order order = Order.builder().user(user).fecha(LocalDate.of(2026, 3, 10))
                .pickupAt(NOW.plusSeconds(3600)).estado(OrderEstado.COMANDADO).creditTotal(1).build();
            order.addItem(OrderItem.builder().dish(dish).category(category).dishNombre("Fideos")
                .dishCategoria(category.getNombre()).creditCost(1).build());
            order = orderRepo.save(order);
            for (int i = 0; i < jobs; i++) {
                jobRepo.save(PrintJob.builder().order(order).station(DishStation.COCINA).host("192.168.0.121")
                    .port(9100).data(new byte[] {0x1B, 0x40}).availableAt(NOW.minusSeconds(1)).build());
            }
            orderId = order.getId();
            userId = user.getId();
            dishId = dish.getId();
            categoryId = category.getId();
            sectionId = section.getId();
        });

        int agents = 4;
        ExecutorService pool = Executors.newFixedThreadPool(agents);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int a = 0; a < agents; a++) {
            futures.add(pool.submit(() -> {
                start.await();
                List<Long> mine = new ArrayList<>();
                // Each agent keeps claiming until the queue is empty.
                for (int round = 0; round < 10; round++) {
                    agentService.claim().forEach(j -> mine.add(j.id()));
                }
                return mine;
            }));
        }
        start.countDown();

        Set<Long> seen = new HashSet<>();
        int total = 0;
        for (Future<List<Long>> f : futures) {
            for (Long id : f.get()) {
                total++;
                assertThat(seen.add(id)).as("job %s claimed twice", id).isTrue();
            }
        }
        pool.shutdown();

        assertThat(total).isEqualTo(jobs);
        assertThat(jobRepo.findByOrderId(orderId)).extracting(PrintJob::getStatus)
            .containsOnly(PrintJobStatus.CLAIMED);
    }
}
