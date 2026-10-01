package com.arias.payments;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditLedgerService;
import com.arias.credits.CreditMovement;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.MovementType;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderDto;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderPlacementService;
import com.arias.orders.OrderRepository;
import com.arias.orders.PlaceOrderV2Request;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unidad B13 (feature b2c-ordering-redesign, pedido del usuario 2026-09-29) —
 * pago parcial: el pedido directo usa primero los almuerzos disponibles del
 * cliente y paga SOLO el resto por Mercado Pago. Todos los escenarios usan un
 * pedido de 2 almuerzos (categoria de costo 2, un item) y un paquete DAY de
 * 2 creditos a 3000 centavos (1500 por credito), con 1 almuerzo disponible
 * salvo que el caso diga otra cosa.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(PartialBalanceDirectCheckoutTest.FixedClockConfig.class)
class PartialBalanceDirectCheckoutTest {

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:40:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final AtomicLong PHONE_SEQ = new AtomicLong(500);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired private CreditPurchaseService purchaseService;
    @Autowired private OrderPlacementService orderPlacementService;
    @Autowired private CreditLedgerService ledgerService;
    @Autowired private CreditPurchaseRepository purchaseRepo;
    @Autowired private CreditPackRepository packRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private CreditWalletRepository walletRepo;
    @Autowired private CreditMovementRepository movementRepo;
    @Autowired private EntityManager entityManager;

    @MockitoBean
    private PaymentGateway paymentGateway;

    // ─── fixtures ───────────────────────────────────────────────────────────

    private User user;
    private Dish dish;

    private void givenCustomerWith(int available) {
        user = userRepo.save(User.builder()
            .email("partial-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone("+549" + (1155660100L + PHONE_SEQ.incrementAndGet()))
            .nickname("Apodo-" + System.nanoTime())
            .build());
        if (available > 0) {
            walletRepo.saveAndFlush(CreditWallet.builder()
                .userId(user.getId()).available(available).committed(0)
                .expiresAt(FIXED_NOW.plus(30, ChronoUnit.DAYS)).build());
        }
        packRepo.save(CreditPack.builder()
            .code("DAY").packType(com.arias.credits.packs.CreditPackType.INDIVIDUAL).nombre("Día").creditAmount(2).priceCents(3_000L)
            .discountPercent(0).ordenDisplay(0).enabled(true).build());
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(2).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        dish = dishRepo.save(Dish.builder()
            .nombre("Plato-" + System.nanoTime()).category(category).menuSection(section)
            .enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-partial", "https://mp.test/init-partial"));
    }

    private static Instant pickupAt() {
        return FIXED_NOW.plus(90, ChronoUnit.MINUTES);
    }

    private PlaceOrderV2Request request() {
        return new PlaceOrderV2Request(
            List.of(new PlaceOrderV2Request.OrderItemRequest(dish.getId(), null, null)),
            pickupAt(), null);
    }

    private DirectCheckoutDto checkout() {
        DirectCheckoutDto dto = purchaseService.createDirectCheckout(user.getId(), request());
        entityManager.flush();
        entityManager.clear();
        return dto;
    }

    private CreditWallet wallet() {
        entityManager.flush();
        entityManager.clear();
        return walletRepo.findById(user.getId()).orElseGet(() -> CreditWallet.emptyFor(user.getId()));
    }

    private List<CreditMovement> movements() {
        return movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    private void assertWallet(int available, int committed) {
        CreditWallet w = wallet();
        assertThat(w.getAvailable()).as("available").isEqualTo(available);
        assertThat(w.getCommitted()).as("committed").isEqualTo(committed);
    }

    private void snapshot(DirectCheckoutDto dto, PaymentStatus status, long refundedCents) {
        CreditPurchase p = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        purchaseService.applySnapshot(new PaymentSnapshot("mp-" + dto.purchaseId(), status,
            "detail", p.getAmountCents(), "ARS", p.getId().toString(), refundedCents));
        entityManager.flush();
        entityManager.clear();
    }

    private Order order(DirectCheckoutDto dto) {
        return orderRepo.findById(dto.orderId()).orElseThrow();
    }

    private int stock() {
        return dishRepo.findById(dish.getId()).orElseThrow().getStockActual();
    }

    // ─── creacion ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("createDirectCheckout(): con 1 disponible y un pedido de 2 reserva 1 del saldo y la compra DIRECT es solo por 1 credito")
    void partialBalanceReservesAvailableAndChargesOnlyTheRest() {
        givenCustomerWith(1);

        DirectCheckoutDto dto = checkout();

        assertWallet(0, 1);
        Order order = order(dto);
        assertThat(order.getEstado()).isEqualTo(OrderEstado.PENDIENTE_PAGO);
        assertThat(order.getCreditTotal()).isEqualTo(2);
        assertThat(order.getCreditsFromBalance()).isEqualTo(1);

        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getCreditAmount()).isEqualTo(1);
        assertThat(purchase.getAmountCents()).isEqualTo(1_500L);

        List<CreditMovement> movements = movements();
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getType()).isEqualTo(MovementType.COMMIT);
        assertThat(movements.get(0).getDeltaAvailable()).isEqualTo(-1);
        assertThat(movements.get(0).getDeltaCommitted()).isEqualTo(1);
        assertThat(movements.get(0).getOrderId()).isEqualTo(dto.orderId());
        assertThat(movements.get(0).getDescription()).contains("#" + dto.orderId());
    }

    @Test
    @DisplayName("createDirectCheckout(): con 0 disponibles se mantiene el comportamiento anterior (importe completo, nada del saldo)")
    void noBalanceKeepsTheFullMercadoPagoAmount() {
        givenCustomerWith(0);

        DirectCheckoutDto dto = checkout();

        assertThat(order(dto).getCreditsFromBalance()).isZero();
        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getCreditAmount()).isEqualTo(2);
        assertThat(purchase.getAmountCents()).isEqualTo(3_000L);
        assertThat(movements()).isEmpty();
    }

    @Test
    @DisplayName("createDirectCheckout(): si el saldo alcanza para el pedido entero se rechaza con 409 y nunca se crea una compra de importe cero")
    void enoughBalanceRefusesDirectCheckout() {
        givenCustomerWith(2);

        assertThatThrownBy(() -> purchaseService.createDirectCheckout(user.getId(), request()))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "balance-covers-order");

        assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
    }

    @Test
    @DisplayName("createDirectCheckout(): los almuerzos vencidos no se usan — cuenta como 0 disponibles y se paga todo")
    void expiredAvailableIsNotUsed() {
        givenCustomerWith(1);
        CreditWallet w = walletRepo.findById(user.getId()).orElseThrow();
        w.setExpiresAt(FIXED_NOW.minus(1, ChronoUnit.HOURS));
        walletRepo.saveAndFlush(w);

        DirectCheckoutDto dto = checkout();

        assertThat(order(dto).getCreditsFromBalance()).isZero();
        assertThat(purchaseRepo.findById(dto.purchaseId()).orElseThrow().getCreditAmount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Vencimiento: los almuerzos reservados (committed) por un pedido esperando pago no vencen")
    void reservedLunchesDoNotExpire() {
        givenCustomerWith(1);
        checkout();

        CreditWallet w = walletRepo.findById(user.getId()).orElseThrow();
        w.setExpiresAt(FIXED_NOW.minus(1, ChronoUnit.HOURS));
        walletRepo.saveAndFlush(w);
        ledgerService.expireIfDue(user.getId());

        assertWallet(0, 1);
    }

    @Test
    @DisplayName("Migracion V28: la base rechaza credits_from_balance mayor que credit_total")
    void databaseRejectsCreditsFromBalanceAboveCreditTotal() {
        givenCustomerWith(0);
        DirectCheckoutDto dto = checkout();

        Order order = order(dto);
        order.setCreditsFromBalance(order.getCreditTotal() + 1);
        assertThatThrownBy(() -> orderRepo.saveAndFlush(order))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("OrderDto: expone creditsFromBalance en el listado del cliente")
    void orderDtoExposesCreditsFromBalance() {
        givenCustomerWith(1);
        DirectCheckoutDto dto = checkout();

        OrderDto listed = orderPlacementService.list(user.getId()).stream()
            .filter(o -> o.id().equals(dto.orderId())).findFirst().orElseThrow();

        assertThat(listed.creditsFromBalance()).isEqualTo(1);
        assertThat(listed.creditTotal()).isEqualTo(2);
    }

    // ─── aprobacion ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Aprobacion: committed suma el total del pedido y available no se toca")
    void approvalCommitsTheTotalAndLeavesAvailableAlone() {
        givenCustomerWith(1);
        DirectCheckoutDto dto = checkout();

        snapshot(dto, PaymentStatus.APPROVED, 0L);

        assertWallet(0, 2);
        assertThat(order(dto).getEstado()).isEqualTo(OrderEstado.PENDIENTE);
        assertThat(movements()).extracting(CreditMovement::getType)
            .containsExactlyInAnyOrder(MovementType.COMMIT, MovementType.DIRECT_PURCHASE);
    }

    @Test
    @DisplayName("Cancelar despues de la aprobacion libera el total del pedido (2), no solo la parte comprada")
    void cancelAfterApprovalReleasesTheWholeTotal() {
        givenCustomerWith(1);
        DirectCheckoutDto dto = checkout();
        snapshot(dto, PaymentStatus.APPROVED, 0L);

        orderPlacementService.cancel(user.getId(), dto.orderId());

        assertWallet(2, 0);
        assertThat(order(dto).getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(stock()).isEqualTo(5);
    }

    // ─── cierre sin pago ────────────────────────────────────────────────────

    @Test
    @DisplayName("Pago rechazado: libera exactamente lo reservado del saldo, restaura stock y es idempotente")
    void rejectionReleasesTheBalancePartOnce() {
        givenCustomerWith(1);
        DirectCheckoutDto dto = checkout();
        assertThat(stock()).isEqualTo(4);

        snapshot(dto, PaymentStatus.REJECTED, 0L);
        assertWallet(1, 0);
        assertThat(order(dto).getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(stock()).isEqualTo(5);

        snapshot(dto, PaymentStatus.REJECTED, 0L);
        assertWallet(1, 0);
        assertThat(movements()).hasSize(2);
    }

    @Test
    @DisplayName("Pago expirado: libera exactamente lo reservado del saldo y es idempotente")
    void expiryReleasesTheBalancePartOnce() {
        givenCustomerWith(1);
        DirectCheckoutDto dto = checkout();

        purchaseService.expirePendingPurchase(dto.purchaseId());
        assertWallet(1, 0);
        assertThat(stock()).isEqualTo(5);

        purchaseService.expirePendingPurchase(dto.purchaseId());
        assertWallet(1, 0);
        assertThat(movements()).hasSize(2);
    }

    @Test
    @DisplayName("Corte: libera exactamente lo reservado del saldo, restaura stock y es idempotente")
    void cutoffReleasesTheBalancePartOnce() {
        givenCustomerWith(1);
        DirectCheckoutDto dto = checkout();

        orderPlacementService.closeAtCutoff(dto.orderId());
        assertWallet(1, 0);
        assertThat(order(dto).getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(stock()).isEqualTo(5);

        orderPlacementService.closeAtCutoff(dto.orderId());
        assertWallet(1, 0);
        assertThat(movements()).hasSize(2);
    }

    @Test
    @DisplayName("Cancelacion del cliente de un pedido esperando pago: libera lo reservado del saldo y una segunda cancelacion se rechaza sin doble liberacion")
    void customerCancelReleasesTheBalancePartOnce() {
        givenCustomerWith(1);
        DirectCheckoutDto dto = checkout();

        orderPlacementService.cancel(user.getId(), dto.orderId());
        assertWallet(1, 0);
        assertThat(stock()).isEqualTo(5);

        assertThatThrownBy(() -> orderPlacementService.cancel(user.getId(), dto.orderId()))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-locked");
        assertWallet(1, 0);
        assertThat(movements()).hasSize(2);
    }

    @Test
    @DisplayName("Aprobacion tardia despues del corte: solo lo comprado (1) va a available, sin doble liberacion")
    void lateApprovalAfterCutoffCreditsOnlyThePurchasedPart() {
        givenCustomerWith(1);
        DirectCheckoutDto dto = checkout();
        orderPlacementService.closeAtCutoff(dto.orderId());
        entityManager.flush();
        entityManager.clear();

        snapshot(dto, PaymentStatus.APPROVED, 0L);

        // 1 liberado por el corte + 1 comprado que se acredita a disponibles.
        assertWallet(2, 0);
        assertThat(order(dto).getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(movements()).extracting(CreditMovement::getType)
            .containsExactlyInAnyOrder(MovementType.COMMIT, MovementType.RELEASE,
                MovementType.DIRECT_PURCHASE_REFUND);
    }

    // ─── reembolso ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Reembolso total: la reversion solo toca la parte comprada (1), nunca lo reservado del saldo")
    void refundOnlyReversesThePurchasedPart() {
        givenCustomerWith(1);
        DirectCheckoutDto dto = checkout();
        snapshot(dto, PaymentStatus.APPROVED, 0L);

        // Un saldo disponible ajeno al pedido para que la reversion tenga de donde tomar.
        CreditWallet w = walletRepo.findById(user.getId()).orElseThrow();
        w.setAvailable(5);
        walletRepo.saveAndFlush(w);

        snapshot(dto, PaymentStatus.REFUNDED, 1_500L);

        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getCreditsReversed()).isEqualTo(1);
        assertThat(purchase.getStatus()).isEqualTo(CreditPurchaseStatus.REVERSED);
        assertWallet(4, 2);
    }
}
