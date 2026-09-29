package com.arias.payments;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.security.JwtUser;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderItem;
import com.arias.orders.OrderRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.jpa.repository.Query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/credits/purchases/pending} (feature b2c-ordering-redesign,
 * tarea B14): las compras {@code PENDING} vivas (creadas en las últimas
 * {@link PaymentReconciliationScheduler#PENDING_EXPIRE_HOURS} horas, la misma
 * ventana con la que {@link PaymentReconciliationScheduler} las expira) del
 * usuario autenticado, más nuevas primero. Alimenta el aviso de pago pendiente
 * de "Mis almuerzos". Unidad B14.1: además, ni el listado ni {@code GET /{id}}
 * hacen una consulta por fila o por relación {@code LAZY}.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class PendingPurchasesTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong();

    @Autowired private CreditPurchaseService purchaseService;
    @Autowired private CreditPurchaseRepository purchaseRepo;
    @Autowired private CreditPackRepository packRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private EntityManager entityManager;
    @Autowired private SessionFactory sessionFactory;
    @Autowired private WebApplicationContext webContext;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("returns only the caller's PENDING purchases within the pending window, newest first")
    void returnsOnlyCallersAlivePendingNewestFirst() {
        User me = persistUser("pending-me");
        User other = persistUser("pending-other");
        CreditPack pack = persistPack();

        CreditPurchase older = persistPackPurchase(me, pack, CreditPurchaseStatus.PENDING, hoursAgo(5));
        CreditPurchase newest = persistPackPurchase(me, pack, CreditPurchaseStatus.PENDING, hoursAgo(1));
        CreditPurchase middle = persistPackPurchase(me, pack, CreditPurchaseStatus.PENDING, hoursAgo(3));
        persistPackPurchase(other, pack, CreditPurchaseStatus.PENDING, hoursAgo(1));
        settle();

        List<CreditPurchaseDto> result = purchaseService.listPendingPurchases(me.getId());

        assertThat(result).extracting(CreditPurchaseDto::id)
            .containsExactly(newest.getId(), middle.getId(), older.getId());
    }

    @Test
    @DisplayName("excludes APPROVED, REJECTED and other terminal purchases, and PENDING ones older than the pending window")
    void excludesNonPendingAndExpired() {
        User me = persistUser("pending-excl");
        CreditPack pack = persistPack();

        CreditPurchase alive = persistPackPurchase(me, pack, CreditPurchaseStatus.PENDING, hoursAgo(2));
        persistPackPurchase(me, pack, CreditPurchaseStatus.APPROVED, hoursAgo(2));
        persistPackPurchase(me, pack, CreditPurchaseStatus.REJECTED, hoursAgo(2));
        persistPackPurchase(me, pack, CreditPurchaseStatus.EXPIRED, hoursAgo(2));
        persistPackPurchase(me, pack, CreditPurchaseStatus.PENDING, hoursAgo(PaymentReconciliationScheduler.PENDING_EXPIRE_HOURS + 1));
        settle();

        List<CreditPurchaseDto> result = purchaseService.listPendingPurchases(me.getId());

        assertThat(result).extracting(CreditPurchaseDto::id).containsExactly(alive.getId());
    }

    @Test
    @DisplayName("a PENDING purchase just inside the pending window is listed, one just past it is not")
    void windowBoundaryMatchesTheReconciliationScheduler() {
        User me = persistUser("pending-edge");
        CreditPack pack = persistPack();
        Instant cutoff = Instant.now().minus(PaymentReconciliationScheduler.PENDING_EXPIRE_HOURS, ChronoUnit.HOURS);

        CreditPurchase inside = persistPackPurchase(me, pack, CreditPurchaseStatus.PENDING,
            cutoff.plus(5, ChronoUnit.MINUTES));
        persistPackPurchase(me, pack, CreditPurchaseStatus.PENDING, cutoff.minus(5, ChronoUnit.MINUTES));
        settle();

        assertThat(purchaseService.listPendingPurchases(me.getId()))
            .extracting(CreditPurchaseDto::id).containsExactly(inside.getId());
    }

    @Test
    @DisplayName("a DIRECT purchase carries its orderId, a PACK purchase carries the pack name and no orderId")
    void directHasOrderIdPackDoesNot() {
        User me = persistUser("pending-shape");
        CreditPack pack = persistPack();
        Order order = persistOrderAwaitingPayment(me, persistDish(), 2);

        CreditPurchase direct = persistDirectPurchase(me, order, hoursAgo(1));
        CreditPurchase packPurchase = persistPackPurchase(me, pack, CreditPurchaseStatus.PENDING, hoursAgo(2));
        settle();

        List<CreditPurchaseDto> result = purchaseService.listPendingPurchases(me.getId());

        CreditPurchaseDto directDto = result.stream()
            .filter(d -> d.id().equals(direct.getId())).findFirst().orElseThrow();
        CreditPurchaseDto packDto = result.stream()
            .filter(d -> d.id().equals(packPurchase.getId())).findFirst().orElseThrow();
        assertThat(directDto.type()).isEqualTo(PurchaseType.DIRECT);
        assertThat(directDto.orderId()).isEqualTo(order.getId());
        assertThat(directDto.orderEstado()).isEqualTo(OrderEstado.PENDIENTE_PAGO);
        assertThat(directDto.packNombre()).isNull();
        assertThat(directDto.creditAmount()).isEqualTo(2);
        assertThat(directDto.amountCents()).isEqualTo(3_000L);
        assertThat(directDto.currency()).isEqualTo("ARS");
        assertThat(packDto.type()).isEqualTo(PurchaseType.PACK);
        assertThat(packDto.orderId()).isNull();
        assertThat(packDto.orderEstado()).isNull();
        assertThat(packDto.packNombre()).isEqualTo("Paquete Semana");
    }

    @Test
    @DisplayName("a DIRECT purchase whose order is CANCELADO is still listed, with orderEstado CANCELADO")
    void directWithCancelledOrderExposesItsEstado() {
        User me = persistUser("pending-cancelled");
        Order order = persistOrderAwaitingPayment(me, persistDish(), 2);
        order.setEstado(OrderEstado.CANCELADO);
        orderRepo.save(order);
        CreditPurchase direct = persistDirectPurchase(me, order, hoursAgo(1));
        settle();

        List<CreditPurchaseDto> result = purchaseService.listPendingPurchases(me.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(direct.getId());
        assertThat(result.get(0).orderId()).isEqualTo(order.getId());
        assertThat(result.get(0).orderEstado()).isEqualTo(OrderEstado.CANCELADO);
    }

    @Test
    @DisplayName("is empty when the caller has no pending purchases")
    void emptyWhenNone() {
        User me = persistUser("pending-empty");

        assertThat(purchaseService.listPendingPurchases(me.getId())).isEmpty();
    }

    @Test
    @DisplayName("HTTP: /pending is routed to the list endpoint (not captured by /{id}) and returns the expected JSON")
    void pendingPathIsNotTreatedAsAnId() throws Exception {
        User me = persistUser("pending-http");
        CreditPack pack = persistPack();
        CreditPurchase purchase = persistPackPurchase(me, pack, CreditPurchaseStatus.PENDING, hoursAgo(1));
        settle();

        mockMvc.perform(get("/api/v1/credits/purchases/pending").with(authentication(auth(me))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(purchase.getId().toString()))
            .andExpect(jsonPath("$[0].type").value("PACK"))
            .andExpect(jsonPath("$[0].creditAmount").value(20))
            .andExpect(jsonPath("$[0].amountCents").value(45_000))
            .andExpect(jsonPath("$[0].currency").value("ARS"))
            .andExpect(jsonPath("$[0].status").value("PENDING"))
            .andExpect(jsonPath("$[0].orderId").doesNotExist())
            .andExpect(jsonPath("$[0].orderEstado").doesNotExist())
            .andExpect(jsonPath("$[0].packNombre").value("Paquete Semana"))
            .andExpect(jsonPath("$[0].createdAt").exists())
            .andExpect(jsonPath("$[0].initPoint").doesNotExist());
    }

    @Test
    @DisplayName("HTTP: a real id still resolves through /{id}")
    void idPathStillWorks() throws Exception {
        User me = persistUser("pending-http-id");
        CreditPurchase purchase = persistPackPurchase(me, persistPack(), CreditPurchaseStatus.PENDING, hoursAgo(1));
        settle();

        mockMvc.perform(get("/api/v1/credits/purchases/" + purchase.getId()).with(authentication(auth(me))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(purchase.getId().toString()));
    }

    @Test
    @DisplayName("HTTP: an unauthenticated request is rejected")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/credits/purchases/pending"))
            .andExpect(status().is4xxClientError());
    }

    // ─── B14.1: sin consultas de más ────────────────────────────────────────

    @Test
    @DisplayName("GET /{id} of a DIRECT purchase reads purchase and order in ONE statement")
    void getPurchaseOfADirectPurchaseIsOneStatement() {
        User me = persistUser("pending-one-direct");
        Order order = persistOrderAwaitingPayment(me, persistDish(), 2);
        CreditPurchase direct = persistDirectPurchase(me, order, hoursAgo(1));
        settle();
        Statistics stats = freshStatistics();

        CreditPurchaseDto dto = purchaseService.getPurchase(me.getId(), direct.getId());

        assertThat(dto.orderId()).isEqualTo(order.getId());
        assertThat(dto.orderEstado()).isEqualTo(OrderEstado.PENDIENTE_PAGO);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /{id} of a PACK purchase reads purchase and pack in ONE statement")
    void getPurchaseOfAPackPurchaseIsOneStatement() {
        User me = persistUser("pending-one-pack");
        CreditPurchase purchase = persistPackPurchase(me, persistPack(), CreditPurchaseStatus.PENDING, hoursAgo(1));
        settle();
        Statistics stats = freshStatistics();

        CreditPurchaseDto dto = purchaseService.getPurchase(me.getId(), purchase.getId());

        assertThat(dto.packNombre()).isEqualTo("Paquete Semana");
        assertThat(dto.orderEstado()).isNull();
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the pending list is ONE statement whatever the number of purchases (no N+1)")
    void pendingListIsOneStatement() {
        User me = persistUser("pending-list-one");
        CreditPack pack = persistPack();
        for (int i = 0; i < 3; i++) {
            persistDirectPurchase(me, persistOrderAwaitingPayment(me, persistDish(), 2), hoursAgo(1 + i));
        }
        persistPackPurchase(me, pack, CreditPurchaseStatus.PENDING, hoursAgo(5));
        settle();
        Statistics stats = freshStatistics();

        List<CreditPurchaseDto> result = purchaseService.listPendingPurchases(me.getId());

        assertThat(result).hasSize(4);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the pending query uses the PENDING literal (so the partial index applies), not a bound status")
    void pendingQueryUsesTheLiteralStatus() throws Exception {
        String jpql = CreditPurchaseRepository.class
            .getMethod("findAlivePendingByUser", Long.class, Instant.class)
            .getAnnotation(Query.class).value();

        assertThat(jpql).contains("CreditPurchaseStatus.PENDING").doesNotContain(":status");
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private Statistics freshStatistics() {
        Statistics stats = sessionFactory.getStatistics();
        stats.clear();
        return stats;
    }

    private static Instant hoursAgo(long hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS);
    }

    private static UsernamePasswordAuthenticationToken auth(User u) {
        JwtUser principal = new JwtUser(u.getId(), u.getEmail(), Role.EMPLOYEE, null, null);
        return new UsernamePasswordAuthenticationToken(principal, null,
            List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
    }

    /** Vuelca lo pendiente y limpia el contexto para que la consulta lea de la base. */
    private void settle() {
        entityManager.flush();
        entityManager.clear();
    }

    private User persistUser(String prefix) {
        return userRepo.save(User.builder()
            .email(prefix + "-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone("+549" + (1133550100L + PHONE_SEQ.incrementAndGet()))
            .nickname("Apodo-" + System.nanoTime())
            .build());
    }

    private CreditPack persistPack() {
        return packRepo.save(CreditPack.builder()
            .code("WEEK-" + UUID.randomUUID().toString().substring(0, 8))
            .nombre("Paquete Semana")
            .creditAmount(20)
            .priceCents(45_000L)
            .discountPercent(10)
            .ordenDisplay(0)
            .enabled(true)
            .build());
    }

    /** {@code created_at} lo fija {@code @CreationTimestamp}; se reescribe por SQL para simular la antigüedad. */
    private CreditPurchase persistPackPurchase(User user, CreditPack pack, CreditPurchaseStatus status,
                                               Instant createdAt) {
        CreditPurchase purchase = purchaseRepo.save(CreditPurchase.builder()
            .user(user)
            .type(PurchaseType.PACK)
            .pack(pack)
            .creditAmount(20)
            .amountCents(45_000L)
            .currency("ARS")
            .status(status)
            .build());
        backdate(purchase, createdAt);
        return purchase;
    }

    private CreditPurchase persistDirectPurchase(User user, Order order, Instant createdAt) {
        CreditPurchase purchase = purchaseRepo.save(CreditPurchase.builder()
            .user(user)
            .type(PurchaseType.DIRECT)
            .order(order)
            .creditAmount(2)
            .amountCents(3_000L)
            .currency("ARS")
            .status(CreditPurchaseStatus.PENDING)
            .build());
        backdate(purchase, createdAt);
        return purchase;
    }

    private void backdate(CreditPurchase purchase, Instant createdAt) {
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE credit_purchase SET created_at = :at WHERE id = :id")
            .setParameter("at", createdAt)
            .setParameter("id", purchase.getId())
            .executeUpdate();
    }

    private Dish persistDish() {
        Category category = categoryRepo.save(Category.builder()
            .nombre("Category-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Section-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        return dishRepo.save(Dish.builder()
            .nombre("Dish-" + System.nanoTime())
            .category(category)
            .menuSection(section)
            .enabled(true)
            .especial(false)
            .stockDiarioDefault(5)
            .stockActual(5)
            .build());
    }

    private Order persistOrderAwaitingPayment(User user, Dish dish, int creditTotal) {
        Order order = Order.builder()
            .user(user)
            .fecha(LocalDate.now())
            .pickupAt(Instant.now().plus(2, ChronoUnit.HOURS))
            .estado(OrderEstado.PENDIENTE_PAGO)
            .creditTotal(creditTotal)
            .build();
        order.addItem(OrderItem.builder()
            .dish(dish)
            .dishNombre(dish.getNombre())
            .dishCategoria(dish.getCategory().getNombre())
            .creditCost(creditTotal)
            .build());
        return orderRepo.save(order);
    }
}
