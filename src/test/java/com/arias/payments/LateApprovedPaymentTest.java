package com.arias.payments;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderPlacementService;
import com.arias.orders.OrderRepository;
import com.arias.orders.PlaceOrderV2Request;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unidad B15 — un pago APPROVED que llega cuando la compra ya está cerrada
 * (REJECTED, CANCELLED o EXPIRED) y nunca se acreditó. Antes se ignoraba en
 * silencio: el cliente pagaba y no recibía almuerzos. Casos reales: tarjeta
 * rechazada y reintento con otra tarjeta en el MISMO checkout (otro
 * {@code payment_id}), pago después de las 24 h, y compra cancelada por la
 * compensación de la fase 3 de B13.1/B13.2 con la preferencia todavía viva.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(LateApprovedPaymentTest.FixedClockConfig.class)
class LateApprovedPaymentTest {

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:40:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final AtomicLong PHONE_SEQ = new AtomicLong(900);
    private static final String LATE_WARN = "pago aprobado sobre compra cerrada";

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

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger serviceLogger;
    private User user;
    private Dish dish;

    @BeforeEach
    void captureLogs() {
        serviceLogger = (Logger) LoggerFactory.getLogger(CreditPurchaseService.class);
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        serviceLogger.detachAppender(logs);
    }

    // ─── fixtures ───────────────────────────────────────────────────────────

    private void givenCustomerWith(int available) {
        user = userRepo.save(User.builder()
            .email("late-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone("+549" + (1155880100L + PHONE_SEQ.incrementAndGet()))
            .nickname("Apodo-" + System.nanoTime())
            .build());
        if (available > 0) {
            walletRepo.saveAndFlush(CreditWallet.builder()
                .userId(user.getId()).available(available).committed(0)
                .expiresAt(FIXED_NOW.plus(30, ChronoUnit.DAYS)).build());
        }
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-late", "https://mp.test/init-late"));
    }

    private UUID givenPackPurchase(int creditAmount, long priceCents) {
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("LATE-" + UUID.randomUUID().toString().substring(0, 8)).nombre("Semana")
            .creditAmount(creditAmount).priceCents(priceCents)
            .discountPercent(0).ordenDisplay(0).enabled(true).build());
        UUID id = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null)).purchaseId();
        flushAndClear();
        return id;
    }

    /** Pedido de 2 almuerzos (costo 2) con paquete DAY de 2 créditos a 3000 centavos. */
    private DirectCheckoutDto givenDirectCheckout() {
        packRepo.save(CreditPack.builder()
            .code("DAY").nombre("Día").creditAmount(2).priceCents(3_000L)
            .discountPercent(0).ordenDisplay(0).enabled(true).build());
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(2).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        dish = dishRepo.save(Dish.builder()
            .nombre("Plato-" + System.nanoTime()).category(category).menuSection(section)
            .enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
        DirectCheckoutDto dto = purchaseService.createDirectCheckout(user.getId(), new PlaceOrderV2Request(
            List.of(new PlaceOrderV2Request.OrderItemRequest(dish.getId(), null, null)),
            FIXED_NOW.plus(90, ChronoUnit.MINUTES), null));
        flushAndClear();
        return dto;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private void apply(UUID purchaseId, String paymentId, PaymentStatus status, long refundedCents) {
        CreditPurchase p = purchaseRepo.findById(purchaseId).orElseThrow();
        purchaseService.applySnapshot(new PaymentSnapshot(paymentId, status, "detail",
            p.getAmountCents(), "ARS", purchaseId.toString(), refundedCents));
        flushAndClear();
    }

    private void apply(UUID purchaseId, String paymentId, PaymentStatus status) {
        apply(purchaseId, paymentId, status, 0L);
    }

    private CreditWallet wallet() {
        flushAndClear();
        return walletRepo.findById(user.getId()).orElseGet(() -> CreditWallet.emptyFor(user.getId()));
    }

    private List<CreditMovement> movements(MovementType type) {
        return movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(m -> m.getType() == type).toList();
    }

    private CreditPurchase purchase(UUID id) {
        return purchaseRepo.findById(id).orElseThrow();
    }

    private List<ILoggingEvent> warnsContaining(String text) {
        return logs.list.stream()
            .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(text))
            .toList();
    }

    // ─── PACK ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PACK: REJECTED y luego APPROVED (reintento con otra tarjeta, otro payment_id) acredita los almuerzos en disponibles, deja APPROVED y loguea WARN")
    void packRejectedThenApprovedCreditsAvailable() {
        givenCustomerWith(0);
        UUID id = givenPackPurchase(10, 10_000L);

        apply(id, "mp-card-1", PaymentStatus.REJECTED);
        assertThat(purchase(id).getStatus()).isEqualTo(CreditPurchaseStatus.REJECTED);
        assertThat(wallet().getAvailable()).isZero();

        apply(id, "mp-card-2", PaymentStatus.APPROVED);

        CreditPurchase credited = purchase(id);
        assertThat(credited.getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(credited.getCreditedAt()).isNotNull();
        assertThat(credited.getMpPaymentId()).isEqualTo("mp-card-2");
        assertThat(wallet().getAvailable()).isEqualTo(10);
        assertThat(wallet().getCommitted()).isZero();
        assertThat(movements(MovementType.PACK_PURCHASE)).hasSize(1);

        List<ILoggingEvent> warns = warnsContaining(LATE_WARN);
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage())
            .contains(id.toString(), "REJECTED", "mp-card-2", "10 almuerzos");
    }

    @Test
    @DisplayName("PACK con quantity: la compra tardía acredita el total (creditAmount ya multiplicado)")
    void packWithQuantityCreditsTheMultipliedTotal() {
        givenCustomerWith(0);
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("LATEQ-" + UUID.randomUUID().toString().substring(0, 8)).nombre("Semana")
            .creditAmount(5).priceCents(5_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());
        UUID id = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, 3)).purchaseId();
        flushAndClear();

        apply(id, "mp-q-1", PaymentStatus.REJECTED);
        apply(id, "mp-q-2", PaymentStatus.APPROVED);

        assertThat(purchase(id).getCreditAmount()).isEqualTo(15);
        assertThat(wallet().getAvailable()).isEqualTo(15);
    }

    @Test
    @DisplayName("PACK: EXPIRED (pasaron las 24 h) y luego APPROVED acredita los almuerzos")
    void packExpiredThenApprovedCredits() {
        givenCustomerWith(0);
        UUID id = givenPackPurchase(10, 10_000L);
        purchaseService.expirePendingPurchase(id);
        flushAndClear();
        assertThat(purchase(id).getStatus()).isEqualTo(CreditPurchaseStatus.EXPIRED);

        apply(id, "mp-expired-paid", PaymentStatus.APPROVED);

        assertThat(purchase(id).getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(wallet().getAvailable()).isEqualTo(10);
        assertThat(warnsContaining(LATE_WARN)).hasSize(1);
        assertThat(warnsContaining(LATE_WARN).get(0).getFormattedMessage()).contains("EXPIRED");
    }

    @Test
    @DisplayName("PACK: un APPROVED duplicado (webhook repetido o reconciliación) acredita una sola vez")
    void duplicateApprovedWebhookCreditsOnce() {
        givenCustomerWith(0);
        UUID id = givenPackPurchase(10, 10_000L);
        apply(id, "mp-card-1", PaymentStatus.REJECTED);
        apply(id, "mp-card-2", PaymentStatus.APPROVED);
        Instant firstCreditedAt = purchase(id).getCreditedAt();

        apply(id, "mp-card-2", PaymentStatus.APPROVED);
        apply(id, "mp-card-2", PaymentStatus.APPROVED);

        assertThat(wallet().getAvailable()).isEqualTo(10);
        assertThat(movements(MovementType.PACK_PURCHASE)).hasSize(1);
        assertThat(purchase(id).getCreditedAt()).isEqualTo(firstCreditedAt);
        assertThat(warnsContaining(LATE_WARN)).hasSize(1);
    }

    @Test
    @DisplayName("PACK: un SEGUNDO pago aprobado con otro payment_id NO acredita de nuevo, conserva el primero y loguea WARN de reembolso manual")
    void secondDifferentApprovedPaymentDoesNotCreditAgain() {
        givenCustomerWith(0);
        UUID id = givenPackPurchase(10, 10_000L);
        apply(id, "mp-card-1", PaymentStatus.REJECTED);
        apply(id, "mp-card-2", PaymentStatus.APPROVED);

        apply(id, "mp-card-3", PaymentStatus.APPROVED);

        assertThat(wallet().getAvailable()).isEqualTo(10);
        assertThat(movements(MovementType.PACK_PURCHASE)).hasSize(1);
        assertThat(purchase(id).getMpPaymentId()).isEqualTo("mp-card-2");
        List<ILoggingEvent> warns = warnsContaining("reembolso manual");
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains(id.toString(), "mp-card-3", "mp-card-2");
    }

    @Test
    @DisplayName("PACK normal: PENDING -> APPROVED sigue acreditando sin el WARN de compra cerrada; un segundo pago aprobado distinto tampoco duplica")
    void normalApprovalIsUnchangedAndDoublePaymentIsStillSafe() {
        givenCustomerWith(0);
        UUID id = givenPackPurchase(10, 10_000L);

        apply(id, "mp-normal", PaymentStatus.APPROVED);

        assertThat(purchase(id).getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(wallet().getAvailable()).isEqualTo(10);
        assertThat(warnsContaining(LATE_WARN)).isEmpty();

        apply(id, "mp-normal-2", PaymentStatus.APPROVED);
        assertThat(wallet().getAvailable()).isEqualTo(10);
        assertThat(warnsContaining("reembolso manual")).hasSize(1);
    }

    @Test
    @DisplayName("PACK: el rechazo posterior de otro intento (otro payment_id) sobre una compra ya acreditada no la toca")
    void laterRejectionOfAnotherAttemptDoesNotTouchACreditedPurchase() {
        givenCustomerWith(0);
        UUID id = givenPackPurchase(10, 10_000L);
        apply(id, "mp-card-2", PaymentStatus.APPROVED);

        apply(id, "mp-card-1", PaymentStatus.REJECTED);

        assertThat(purchase(id).getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(wallet().getAvailable()).isEqualTo(10);
    }

    @Test
    @DisplayName("PACK: el reembolso total después del crédito tardío revierte los almuerzos (maybeReverse)")
    void refundAfterLateCreditReverses() {
        givenCustomerWith(0);
        UUID id = givenPackPurchase(10, 10_000L);
        apply(id, "mp-card-1", PaymentStatus.REJECTED);
        apply(id, "mp-card-2", PaymentStatus.APPROVED);
        assertThat(wallet().getAvailable()).isEqualTo(10);

        apply(id, "mp-card-2", PaymentStatus.REFUNDED, 10_000L);

        CreditPurchase reversed = purchase(id);
        assertThat(reversed.getStatus()).isEqualTo(CreditPurchaseStatus.REVERSED);
        assertThat(reversed.getCreditsReversed()).isEqualTo(10);
        assertThat(wallet().getAvailable()).isZero();
    }

    @Test
    @DisplayName("PACK: un contracargo después del crédito tardío revierte todo")
    void chargebackAfterLateCreditReverses() {
        givenCustomerWith(0);
        UUID id = givenPackPurchase(10, 10_000L);
        apply(id, "mp-card-1", PaymentStatus.REJECTED);
        apply(id, "mp-card-2", PaymentStatus.APPROVED);

        apply(id, "mp-card-2", PaymentStatus.CHARGED_BACK, 0L);

        assertThat(purchase(id).getStatus()).isEqualTo(CreditPurchaseStatus.REVERSED);
        assertThat(wallet().getAvailable()).isZero();
    }

    // ─── DIRECT ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DIRECT con saldo parcial: REJECTED (pedido cancelado, parte del saldo liberada) y luego APPROVED acredita SOLO la parte comprada a disponibles, sin doble liberación")
    void directPartialRejectedThenApprovedCreditsOnlyThePurchasedPart() {
        givenCustomerWith(1);
        DirectCheckoutDto dto = givenDirectCheckout();
        assertThat(wallet().getAvailable()).isZero();
        assertThat(wallet().getCommitted()).isEqualTo(1);

        apply(dto.purchaseId(), "mp-card-1", PaymentStatus.REJECTED);
        assertThat(orderRepo.findById(dto.orderId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(wallet().getAvailable()).isEqualTo(1);
        assertThat(wallet().getCommitted()).isZero();

        apply(dto.purchaseId(), "mp-card-2", PaymentStatus.APPROVED);

        CreditPurchase credited = purchase(dto.purchaseId());
        assertThat(credited.getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(credited.getCreditedAt()).isNotNull();
        // 1 liberado del saldo + 1 comprado: 2 disponibles, nada comprometido.
        assertThat(wallet().getAvailable()).isEqualTo(2);
        assertThat(wallet().getCommitted()).isZero();
        List<CreditMovement> refunds = movements(MovementType.DIRECT_PURCHASE_REFUND);
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).getDeltaAvailable()).isEqualTo(1);
        // El pedido cancelado no se reabre y el stock queda restaurado una sola vez.
        assertThat(orderRepo.findById(dto.orderId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(5);
        assertThat(warnsContaining(LATE_WARN)).hasSize(1);
    }

    @Test
    @DisplayName("DIRECT: compra CANCELLED por la compensación (pedido cerrado) y luego APPROVED acredita a disponibles")
    void directCancelledByCompensationThenApprovedCredits() {
        givenCustomerWith(0);
        DirectCheckoutDto dto = givenDirectCheckout();
        // Lo que deja la compensación de la fase 3: compra CANCELLED + pedido cerrado.
        CreditPurchase p = purchase(dto.purchaseId());
        p.setStatus(CreditPurchaseStatus.CANCELLED);
        purchaseRepo.saveAndFlush(p);
        orderPlacementService.closeForPaymentFailure(dto.orderId());
        flushAndClear();

        apply(dto.purchaseId(), "mp-orphan-pref", PaymentStatus.APPROVED);

        assertThat(purchase(dto.purchaseId()).getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(wallet().getAvailable()).isEqualTo(2);
        assertThat(wallet().getCommitted()).isZero();
        assertThat(movements(MovementType.DIRECT_PURCHASE_REFUND)).hasSize(1);
        assertThat(orderRepo.findById(dto.orderId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(warnsContaining(LATE_WARN).get(0).getFormattedMessage()).contains("CANCELLED");
    }

    @Test
    @DisplayName("DIRECT: el reembolso después del crédito tardío revierte lo acreditado")
    void directRefundAfterLateCreditReverses() {
        givenCustomerWith(0);
        DirectCheckoutDto dto = givenDirectCheckout();
        apply(dto.purchaseId(), "mp-card-1", PaymentStatus.REJECTED);
        apply(dto.purchaseId(), "mp-card-2", PaymentStatus.APPROVED);
        assertThat(wallet().getAvailable()).isEqualTo(2);

        apply(dto.purchaseId(), "mp-card-2", PaymentStatus.REFUNDED, 3_000L);

        assertThat(purchase(dto.purchaseId()).getStatus()).isEqualTo(CreditPurchaseStatus.REVERSED);
        assertThat(wallet().getAvailable()).isZero();
    }

    // ─── B15.1 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DIRECT con el pedido en un tercer estado (PENDIENTE): el pago aprobado acredita a disponibles, no compromete créditos ni toca el pedido")
    void directApprovalWithTheOrderInAThirdStateCreditsAvailableWithoutCommitting() {
        givenCustomerWith(0);
        DirectCheckoutDto dto = givenDirectCheckout();
        // Estado que el flujo normal no produce (PENDIENTE_PAGO pasa a PENDIENTE sólo al acreditar),
        // fijado a mano para blindar la rama "el pedido ya no espera este pago".
        var order = orderRepo.findById(dto.orderId()).orElseThrow();
        order.setEstado(OrderEstado.PENDIENTE);
        orderRepo.saveAndFlush(order);
        flushAndClear();

        apply(dto.purchaseId(), "mp-third-state", PaymentStatus.APPROVED);

        assertThat(purchase(dto.purchaseId()).getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(wallet().getAvailable()).isEqualTo(2);
        assertThat(wallet().getCommitted()).isZero();
        assertThat(movements(MovementType.DIRECT_PURCHASE_REFUND)).hasSize(1);
        assertThat(movements(MovementType.DIRECT_PURCHASE)).isEmpty();
        assertThat(orderRepo.findById(dto.orderId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.PENDIENTE);

        // Idempotente: un segundo aviso del mismo pago no vuelve a acreditar.
        apply(dto.purchaseId(), "mp-third-state", PaymentStatus.APPROVED);
        assertThat(wallet().getAvailable()).isEqualTo(2);
    }

    @Test
    @DisplayName("Compra en mediación sin acreditar y un pago aprobado con OTRO payment_id: no se acredita, se loguea un WARN que lo explica")
    void differentApprovedPaymentOnAnUncreditedMediationPurchaseIsNotAdopted() {
        givenCustomerWith(0);
        UUID id = givenPackPurchase(10, 10_000L);
        apply(id, "mp-disputed", PaymentStatus.IN_MEDIATION);
        assertThat(purchase(id).getStatus()).isEqualTo(CreditPurchaseStatus.IN_MEDIATION);

        apply(id, "mp-other", PaymentStatus.APPROVED);

        assertThat(purchase(id).getStatus()).isEqualTo(CreditPurchaseStatus.IN_MEDIATION);
        assertThat(purchase(id).getMpPaymentId()).isEqualTo("mp-disputed");
        assertThat(wallet().getAvailable()).isZero();
        List<ILoggingEvent> warns = warnsContaining("mediación");
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains(id.toString(), "mp-other", "mp-disputed");
        assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.ERROR)).isEmpty();
    }
}
