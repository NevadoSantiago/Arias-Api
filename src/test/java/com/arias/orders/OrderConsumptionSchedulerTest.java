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
import com.arias.orders.notifications.OrderUnpaidCancelledEvent;
import com.arias.printing.PrintJob;
import com.arias.printing.PrintJobRepository;
import com.arias.restaurantconfig.RestaurantConfigRepository;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
@RecordApplicationEvents
@Import(OrderConsumptionSchedulerTest.FixedClockConfig.class)
class OrderConsumptionSchedulerTest {

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T15:00:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private Order persistOrderWithDish(User user, Instant pickupAt, OrderEstado estado, int creditTotal) {
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(creditTotal).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        Dish dish = dishRepo.save(Dish.builder()
            .nombre("Plato-" + System.nanoTime()).category(category).menuSection(section)
            .enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
        Order order = persistOrder(user, pickupAt, estado, creditTotal);
        order.addItem(OrderItem.builder().dish(dish).category(category).dishNombre(dish.getNombre())
            .dishCategoria(category.getNombre()).creditCost(creditTotal).build());
        return orderRepo.save(order);
    }


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

    @Autowired
    private PrintJobRepository jobRepo;

    @Autowired
    private RestaurantConfigRepository configRepo;

    private void setLeads(int preparationMinutes, int orderLeadMinutes) {
        var config = configRepo.getSingleton();
        config.setPickupLeadMinutes(preparationMinutes);
        config.setOrderLeadMinutes(orderLeadMinutes);
        configRepo.saveAndFlush(config);
    }

    /** Fija {@code createdAt} (columna con default y no actualizable por JPA) con SQL nativo. */
    private void setCreatedAt(Long orderId, Instant createdAt) {
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE orders SET created_at = :c WHERE id = :id")
            .setParameter("c", createdAt)
            .setParameter("id", orderId)
            .executeUpdate();
        entityManager.clear();
    }

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
    @DisplayName("consumeDueOrders(): comanda y consume créditos de pedidos PENDIENTE cuyo pickup_at - lead <= now")
    void consumeDueOrdersConfirmaYConsumeCreditos() {
        User user = persistUser();
        seedWallet(user.getId(), 0, 5);
        // pickup_at - lead(20) <= now  <=>  pickup_at <= now + 20 -> 10 min ya vencido
        Order due = persistOrder(user, FIXED_NOW.plus(10, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, 5);

        scheduler.consumeDueOrders();

        Order updated = orderRepo.findById(due.getId()).orElseThrow();
        assertThat(updated.getEstado()).isEqualTo(OrderEstado.COMANDADO);
        assertThat(updated.getConfirmedAt()).isEqualTo(FIXED_NOW);
        assertThat(updated.getComandadoAt()).isEqualTo(FIXED_NOW);

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
        assertThat(updated.getEstado()).isEqualTo(OrderEstado.COMANDADO);

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
        setCreatedAt(due.getId(), FIXED_NOW.minus(30, ChronoUnit.MINUTES));

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

    @Test
    @DisplayName("consumeDueOrders(): al cancelar un PENDIENTE_PAGO en el corte publica el evento de mail al cliente")
    void consumeDueOrdersAvisaAlClienteDelPedidoSinPagar(ApplicationEvents events) {
        User user = persistUser();
        Order due = persistOrder(user, FIXED_NOW.plus(10, ChronoUnit.MINUTES), OrderEstado.PENDIENTE_PAGO, 5);
        Order notYetDue = persistOrder(user, FIXED_NOW.plus(30, ChronoUnit.MINUTES), OrderEstado.PENDIENTE_PAGO, 5);
        setCreatedAt(due.getId(), FIXED_NOW.minus(30, ChronoUnit.MINUTES));

        scheduler.consumeDueOrders();

        List<OrderUnpaidCancelledEvent> published = events.stream(OrderUnpaidCancelledEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).orderId()).isEqualTo(due.getId());
        assertThat(published.get(0).userEmail()).isEqualTo(user.getEmail());
        assertThat(orderRepo.findById(notYetDue.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.PENDIENTE_PAGO);
    }

    // ─── Franja de pedido: PENDIENTE_PAGO usa orderLead + gracia de 10 min ───

    @Test
    @DisplayName("consumeDueOrders(): un PENDIENTE_PAGO dentro del corte del order lead pero creado hace menos de 10 min NO se cierra")
    void consumeDueOrdersNoCierraPendientePagoDentroDeLaGracia() {
        setLeads(40, 20);
        User user = persistUser();
        Order fresh = persistOrder(user, FIXED_NOW.plus(10, ChronoUnit.MINUTES), OrderEstado.PENDIENTE_PAGO, 5);
        setCreatedAt(fresh.getId(), FIXED_NOW.minus(5, ChronoUnit.MINUTES));

        scheduler.consumeDueOrders();

        assertThat(orderRepo.findById(fresh.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.PENDIENTE_PAGO);
    }

    @Test
    @DisplayName("consumeDueOrders(): un PENDIENTE_PAGO dentro del corte del order lead y creado hace más de 10 min SÍ se cierra")
    void consumeDueOrdersCierraPendientePagoPasadaLaGracia() {
        setLeads(40, 20);
        User user = persistUser();
        Order stale = persistOrder(user, FIXED_NOW.plus(10, ChronoUnit.MINUTES), OrderEstado.PENDIENTE_PAGO, 5);
        setCreatedAt(stale.getId(), FIXED_NOW.minus(11, ChronoUnit.MINUTES));

        scheduler.consumeDueOrders();

        assertThat(orderRepo.findById(stale.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.CANCELADO);
    }

    @Test
    @DisplayName("consumeDueOrders(): un PENDIENTE_PAGO fuera del corte del order lead no se cierra aunque entre en el tiempo de preparación")
    void consumeDueOrdersPendientePagoUsaElOrderLeadNoLaPreparacion() {
        setLeads(40, 20);
        User user = persistUser();
        // 30 min: dentro de la preparación (40) pero fuera del order lead (20).
        Order order = persistOrder(user, FIXED_NOW.plus(30, ChronoUnit.MINUTES), OrderEstado.PENDIENTE_PAGO, 5);
        setCreatedAt(order.getId(), FIXED_NOW.minus(60, ChronoUnit.MINUTES));

        scheduler.consumeDueOrders();

        assertThat(orderRepo.findById(order.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.PENDIENTE_PAGO);
    }

    @Test
    @DisplayName("consumeDueOrders(): el comandado de PENDIENTE sigue usando el tiempo de preparación")
    void consumeDueOrdersConfirmaPendienteConElTiempoDePreparacion() {
        setLeads(40, 20);
        User user = persistUser();
        seedWallet(user.getId(), 0, 5);
        Order order = persistOrder(user, FIXED_NOW.plus(30, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, 5);

        scheduler.consumeDueOrders();

        assertThat(orderRepo.findById(order.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.COMANDADO);
    }

    // ─── Auto-comandado ───

    @Test
    @DisplayName("consumeDueOrders(): al comandar encola los tickets de impresión una sola vez")
    void consumeDueOrdersEncolaImpresion() {
        User user = persistUser();
        seedWallet(user.getId(), 0, 5);
        Order due = persistOrderWithDish(user, FIXED_NOW.plus(10, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, 5);

        scheduler.consumeDueOrders();
        scheduler.consumeDueOrders();

        List<PrintJob> jobs = jobRepo.findByOrderId(due.getId());
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getAvailableAt()).isEqualTo(FIXED_NOW.plusSeconds(10));
    }

    @Test
    @DisplayName("consumeDueOrders(): un CONFIRMADO vencido (previo al deploy) se comanda sin mover créditos")
    void consumeDueOrdersComandaConfirmadoSinConsumirOtraVez() {
        User user = persistUser();
        seedWallet(user.getId(), 0, 0);
        Order leftover = persistOrderWithDish(user, FIXED_NOW.plus(10, ChronoUnit.MINUTES), OrderEstado.CONFIRMADO, 5);

        scheduler.consumeDueOrders();

        Order updated = orderRepo.findById(leftover.getId()).orElseThrow();
        assertThat(updated.getEstado()).isEqualTo(OrderEstado.COMANDADO);
        assertThat(updated.getComandadoAt()).isEqualTo(FIXED_NOW);
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
        assertThat(jobRepo.findByOrderId(leftover.getId())).hasSize(1);
    }

    @Test
    @DisplayName("consumeDueOrders(): un CONFIRMADO de un día anterior no se comanda ni se imprime")
    void consumeDueOrdersIgnoraConfirmadoDeDiasAnteriores() {
        User user = persistUser();
        Order yesterday = persistOrderWithDish(user, FIXED_NOW.minus(1, ChronoUnit.DAYS), OrderEstado.CONFIRMADO, 5);

        scheduler.consumeDueOrders();

        assertThat(orderRepo.findById(yesterday.getId()).orElseThrow().getEstado())
            .isEqualTo(OrderEstado.CONFIRMADO);
        assertThat(jobRepo.findByOrderId(yesterday.getId())).isEmpty();
    }

    @Test
    @DisplayName("consumeDueOrders(): un CONFIRMADO que todavía no venció queda como está")
    void consumeDueOrdersIgnoraConfirmadoNoVencido() {
        User user = persistUser();
        Order notDue = persistOrder(user, FIXED_NOW.plus(30, ChronoUnit.MINUTES), OrderEstado.CONFIRMADO, 5);

        scheduler.consumeDueOrders();

        assertThat(orderRepo.findById(notDue.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.CONFIRMADO);
    }

    @Test
    @DisplayName("consumeDueOrders(): re-ejecutar no vuelve a consumir créditos ni a encolar")
    void consumeDueOrdersEsIdempotente() {
        User user = persistUser();
        seedWallet(user.getId(), 0, 5);
        Order due = persistOrderWithDish(user, FIXED_NOW.plus(10, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, 5);

        scheduler.consumeDueOrders();
        scheduler.consumeDueOrders();

        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).hasSize(1);
        assertThat(jobRepo.findByOrderId(due.getId())).hasSize(1);
    }
}
