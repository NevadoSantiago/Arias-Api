package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.credits.CreditMovement;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.MovementType;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unidad 8 — {@link OrderConsumptionScheduler}: diseño §Decisión 4 ("consumo
 * en pickup − lead = job programado más re-validación perezosa"). {@code now}
 * fijo, {@code pickup_lead_minutes} = 20 (default de V20).
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(OrderConsumptionSchedulerTest.FixedClockConfig.class)
class OrderConsumptionSchedulerTest {

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T15:00:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired
    private OrderConsumptionScheduler scheduler;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private CreditWalletRepository walletRepo;

    @Autowired
    private CreditMovementRepository movementRepo;

    @Autowired
    private CategoryRepository categoryRepo;

    @Autowired
    private MenuSectionRepository menuSectionRepo;

    @Autowired
    private DishRepository dishRepo;

    @Autowired
    private EntityManager entityManager;

    private User persistUser() {
        User user = User.builder()
            .email("consume-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .build();
        return userRepo.save(user);
    }

    private void seedWallet(Long userId, int available, int committed) {
        walletRepo.saveAndFlush(CreditWallet.builder()
            .userId(userId)
            .available(available)
            .committed(committed)
            .build());
    }

    private Order persistOrder(User user, Instant pickupAt, OrderEstado estado, int creditTotal) {
        return orderRepo.save(Order.builder()
            .user(user)
            .fecha(LocalDate.ofInstant(pickupAt, ZONE))
            .pickupAt(pickupAt)
            .estado(estado)
            .creditTotal(creditTotal)
            .build());
    }

    @Test
    @DisplayName("consumeDueOrders(): confirma y consume créditos de pedidos PENDIENTE cuyo pickup_at - lead <= now")
    void consumeDueOrdersConfirmaYConsumeCreditos() {
        User user = persistUser();
        seedWallet(user.getId(), 0, 5);
        // pickup_at - lead(20) <= now  <=>  pickup_at <= now + 20 -> 10 min ya vencido
        Order due = persistOrder(user, FIXED_NOW.plus(10, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, 5);

        scheduler.consumeDueOrders();

        Order updated = orderRepo.findById(due.getId()).orElseThrow();
        assertThat(updated.getEstado()).isEqualTo(OrderEstado.CONFIRMADO);
        assertThat(updated.getConfirmedAt()).isEqualTo(FIXED_NOW);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isZero();
        assertThat(wallet.getCommitted()).isZero();

        List<CreditMovement> movements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(movements).anySatisfy(m -> {
            assertThat(m.getType()).isEqualTo(MovementType.CONSUME);
            assertThat(m.getDeltaCommitted()).isEqualTo(-5);
            assertThat(m.getDeltaAvailable()).isZero();
            assertThat(m.getOrderId()).isEqualTo(due.getId());
        });
    }

    @Test
    @DisplayName("consumeDueOrders(): no toca pedidos cuyo horario de retiro todavía respeta el lead")
    void consumeDueOrdersIgnoraPedidosNoVencidos() {
        User user = persistUser();
        seedWallet(user.getId(), 0, 5);
        // pickup_at - lead(20) > now -> todavía no llega el punto de consumo
        Order notYetDue = persistOrder(user, FIXED_NOW.plus(30, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, 5);

        scheduler.consumeDueOrders();

        Order unchanged = orderRepo.findById(notYetDue.getId()).orElseThrow();
        assertThat(unchanged.getEstado()).isEqualTo(OrderEstado.PENDIENTE);
        assertThat(unchanged.getConfirmedAt()).isNull();

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getCommitted()).isEqualTo(5);
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("consumeDueOrders(): un pedido muy atrasado (job caído varios ticks) se procesa igual en el primer tick")
    void consumeDueOrdersProcesaAtrasoEnElPrimerTick() {
        User user = persistUser();
        seedWallet(user.getId(), 0, 5);
        // Bien en el pasado — simula que el cron estuvo caído.
        Order overdue = persistOrder(user, FIXED_NOW.minus(2, ChronoUnit.HOURS), OrderEstado.PENDIENTE, 5);

        scheduler.consumeDueOrders();

        Order updated = orderRepo.findById(overdue.getId()).orElseThrow();
        assertThat(updated.getEstado()).isEqualTo(OrderEstado.CONFIRMADO);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getCommitted()).isZero();
    }

    @Test
    @DisplayName("consumeDueOrders(): nunca toca pedidos CANCELADO aunque el horario ya haya pasado")
    void consumeDueOrdersIgnoraCancelados() {
        User user = persistUser();
        seedWallet(user.getId(), 0, 0);
        Order cancelled = persistOrder(user, FIXED_NOW.minus(1, ChronoUnit.HOURS), OrderEstado.CANCELADO, 5);

        scheduler.consumeDueOrders();

        Order unchanged = orderRepo.findById(cancelled.getId()).orElseThrow();
        assertThat(unchanged.getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(unchanged.getConfirmedAt()).isNull();
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    // ─── Unidad B7: pedidos PENDIENTE_PAGO sin pagar al corte se cancelan ───

    @Test
    @DisplayName("consumeDueOrders(): cancela (no confirma) un pedido PENDIENTE_PAGO cuyo pickup_at - lead <= now, restaura stock y no toca créditos")
    void consumeDueOrdersCancelaPedidosSinPagarVencidos() {
        User user = persistUser();
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(5).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        Dish dish = dishRepo.save(Dish.builder()
            .nombre("Plato-" + System.nanoTime())
            .category(category)
            .menuSection(section)
            .enabled(true)
            .especial(false)
            .stockDiarioDefault(5)
            .stockActual(4) // ya reservado por placeAwaitingPayment (unidad B7)
            .build());

        // pickup_at - lead(20) <= now  <=>  pickup_at <= now + 20 -> 10 min ya vencido
        Order due = persistOrder(user, FIXED_NOW.plus(10, ChronoUnit.MINUTES), OrderEstado.PENDIENTE_PAGO, 5);
        due.addItem(OrderItem.builder()
            .dish(dish)
            .dishNombre(dish.getNombre())
            .dishCategoria(category.getNombre())
            .creditCost(5)
            .build());
        orderRepo.save(due);

        scheduler.consumeDueOrders();

        entityManager.flush();
        entityManager.clear();

        Order updated = orderRepo.findById(due.getId()).orElseThrow();
        assertThat(updated.getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(updated.getCancelledAt()).isEqualTo(FIXED_NOW);
        assertThat(updated.getConfirmedAt()).isNull();

        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(5);

        // Nunca se comprometieron créditos para un pedido PENDIENTE_PAGO —
        // no hay nada que liberar ni consumir.
        assertThat(walletRepo.findById(user.getId())).isEmpty();
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("consumeDueOrders(): no toca un pedido PENDIENTE_PAGO cuyo horario de retiro todavía respeta el lead")
    void consumeDueOrdersIgnoraPendientePagoNoVencido() {
        User user = persistUser();
        Order notYetDue = persistOrder(user, FIXED_NOW.plus(30, ChronoUnit.MINUTES), OrderEstado.PENDIENTE_PAGO, 5);

        scheduler.consumeDueOrders();

        Order unchanged = orderRepo.findById(notYetDue.getId()).orElseThrow();
        assertThat(unchanged.getEstado()).isEqualTo(OrderEstado.PENDIENTE_PAGO);
        assertThat(unchanged.getCancelledAt()).isNull();
    }
}
