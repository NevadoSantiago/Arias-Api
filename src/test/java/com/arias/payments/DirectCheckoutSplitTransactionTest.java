package com.arias.payments;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderPlacementService;
import com.arias.orders.OrderRepository;
import com.arias.orders.PlaceOrderV2Request;
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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unidad B13.1 — {@code createDirectCheckout}/{@code createPurchase} ya NO
 * llaman a Mercado Pago dentro de la transacción que reserva stock y saldo.
 *
 * <p>Esta clase NO es {@code @Transactional}: cada fase del servicio abre y
 * COMMITEA su propia transacción física, así que se observa lo que otro cliente
 * vería de verdad (locks, filas commiteadas). Todo lo que se crea se borra en
 * {@link #cleanUp()} (los commits reales no se revierten solos).
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Import(DirectCheckoutSplitTransactionTest.FixedClockConfig.class)
class DirectCheckoutSplitTransactionTest {

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:40:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final AtomicLong PHONE_SEQ = new AtomicLong();
    /** credit_purchase.mp_preference_id es VARCHAR(100): guardarlo en la fase 3 falla en la base. */
    private static final String TOO_LONG_PREFERENCE_ID = "p".repeat(101);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired private CreditPurchaseService purchaseService;
    @MockitoSpyBean private OrderPlacementService orderPlacementService;
    @Autowired private CreditPurchaseRepository purchaseRepo;
    @Autowired private CreditPackRepository packRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private CreditWalletRepository walletRepo;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private final List<Long> userIds = new ArrayList<>();
    private Long packId;
    /** Solo se borra el pack DAY si lo creó este test; uno preexistente se reutiliza y no se toca. */
    private boolean packCreatedByTest;
    private long dayUnitPriceCents;
    private Long dishId;
    private Long categoryId;
    private Long sectionId;

    @AfterEach
    void cleanUp() {
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM credit_purchase WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM orders WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM credit_movement WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM credit_wallet WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        if (dishId != null) jdbc.update("DELETE FROM dish WHERE id = ?", dishId);
        if (categoryId != null) jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        if (sectionId != null) jdbc.update("DELETE FROM menu_section WHERE id = ?", sectionId);
        if (packId != null && packCreatedByTest) jdbc.update("DELETE FROM credit_pack WHERE id = ?", packId);
    }

    // ─── fixtures ───────────────────────────────────────────────────────────

    private User persistUser() {
        User user = userRepo.save(User.builder()
            .email("split-tx-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone("+549" + (1155770100L + PHONE_SEQ.incrementAndGet()))
            .nickname("Apodo-" + System.nanoTime())
            .build());
        userIds.add(user.getId());
        return user;
    }

    /** Un plato de costo 2 con stock {@code stock}, y el paquete DAY (2 créditos por 3000). */
    private void seedCatalog(int stock) {
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(2).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        Dish dish = dishRepo.save(Dish.builder()
            .nombre("Plato-" + System.nanoTime()).category(category).menuSection(section)
            .enabled(true).especial(false).stockDiarioDefault(stock).stockActual(stock).build());
        // La base de tests es compartida: nunca se asume que el pack DAY no exista. Se
        // reutiliza el habilitado (el mismo que resuelve el servicio) y solo se crea
        // —y luego se borra— si no había ninguno.
        CreditPack pack = packRepo.findByCodeAndDeletedAtIsNullAndEnabledTrue("DAY").orElse(null);
        packCreatedByTest = pack == null;
        if (packCreatedByTest) {
            pack = packRepo.save(CreditPack.builder()
                .code("DAY").nombre("Día").creditAmount(2).priceCents(3_000L)
                .discountPercent(0).ordenDisplay(0).enabled(true).build());
        }
        // Mismo redondeo hacia arriba que CreditPurchaseService.unitPriceCentsFor.
        dayUnitPriceCents = -Math.floorDiv(-pack.getPriceCents(), (long) pack.getCreditAmount());
        categoryId = category.getId();
        sectionId = section.getId();
        dishId = dish.getId();
        packId = pack.getId();
    }

    private PlaceOrderV2Request request() {
        return new PlaceOrderV2Request(
            List.of(new PlaceOrderV2Request.OrderItemRequest(dishId, null, null)),
            FIXED_NOW.plus(60, ChronoUnit.MINUTES),
            null);
    }

    private void seedWallet(Long userId, int available) {
        walletRepo.saveAndFlush(CreditWallet.builder().userId(userId).available(available).committed(0).build());
    }

    private int stock() {
        return dishRepo.findById(dishId).orElseThrow().getStockActual();
    }

    private static UUID purchaseIdOf(CheckoutRequest req) {
        return UUID.fromString(req.externalReference());
    }

    // ─── la regresión clave: MP lento NO bloquea a otros clientes ───────────

    @Test
    @DisplayName("createDirectCheckout(): mientras Mercado Pago no responde, otro cliente puede pedir el MISMO plato (sin locks de stock retenidos)")
    void slowMercadoPagoDoesNotBlockOtherCustomersOrderingTheSameDish() throws Exception {
        seedCatalog(5);
        User payer = persistUser();
        User other = persistUser();
        seedWallet(other.getId(), 10);

        CountDownLatch insideGateway = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        when(paymentGateway.createCheckout(any())).thenAnswer(inv -> {
            insideGateway.countDown();
            releaseGateway.await(30, TimeUnit.SECONDS);
            return new CheckoutSession("pref-slow", "https://mp.test/init-slow");
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DirectCheckoutDto> slowCheckout = pool.submit(
                () -> purchaseService.createDirectCheckout(payer.getId(), request()));
            assertThat(insideGateway.await(10, TimeUnit.SECONDS))
                .as("createDirectCheckout llegó a la llamada a Mercado Pago").isTrue();

            // Mercado Pago sigue "colgado": otro cliente pide el mismo plato.
            Future<?> otherOrder = pool.submit(() -> orderPlacementService.place(other.getId(), request()));
            try {
                otherOrder.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                otherOrder.cancel(true);
                throw new AssertionError(
                    "el pedido de otro cliente quedó esperando el lock de stock mientras Mercado Pago no responde", e);
            }
            // 5 - 1 (pagador, reservado) - 1 (otro cliente)
            assertThat(stock()).isEqualTo(3);
        } finally {
            releaseGateway.countDown();
            pool.shutdown();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    // ─── camino feliz ───────────────────────────────────────────────────────

    @Test
    @DisplayName("createDirectCheckout(): camino feliz — persiste preferenceId e initPoint y devuelve la misma respuesta")
    void happyPathPersistsPreferenceAndInitPoint() {
        seedCatalog(5);
        User payer = persistUser();
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-ok", "https://mp.test/init-ok"));

        DirectCheckoutDto dto = purchaseService.createDirectCheckout(payer.getId(), request());

        assertThat(dto.initPoint()).isEqualTo("https://mp.test/init-ok");
        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getStatus()).isEqualTo(CreditPurchaseStatus.PENDING);
        assertThat(purchase.getMpPreferenceId()).isEqualTo("pref-ok");
        assertThat(purchase.getInitPoint()).isEqualTo("https://mp.test/init-ok");
        assertThat(purchase.getAmountCents()).isEqualTo(dayUnitPriceCents * 2); // el plato cuesta 2 créditos
        assertThat(orderRepo.findById(dto.orderId()).orElseThrow().getEstado())
            .isEqualTo(OrderEstado.PENDIENTE_PAGO);
        assertThat(stock()).isEqualTo(4);

        // Y retomarlo devuelve el mismo initPoint (nunca un segundo checkout).
        assertThat(purchaseService.resumeDirectCheckout(payer.getId(), dto.orderId()).initPoint())
            .isEqualTo("https://mp.test/init-ok");
    }

    // ─── falla del gateway: compensación ────────────────────────────────────

    @Test
    @DisplayName("createDirectCheckout(): si Mercado Pago falla, cancela el pedido, restaura stock, libera lo reservado del saldo, marca la compra CANCELLED y propaga el MISMO error")
    void gatewayFailureCompensatesAndRethrowsTheSameError() {
        seedCatalog(5);
        User payer = persistUser();
        seedWallet(payer.getId(), 1); // pedido de 2 créditos: 1 del saldo + 1 por Mercado Pago
        when(paymentGateway.createCheckout(any())).thenThrow(new BusinessException(
            HttpStatus.BAD_GATEWAY, "mercadopago-checkout-failed", "No se pudo iniciar el pago con Mercado Pago."));

        assertThatThrownBy(() -> purchaseService.createDirectCheckout(payer.getId(), request()))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo("mercadopago-checkout-failed");
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            });

        Order order = orderRepo.findAll().stream()
            .filter(o -> o.getUser().getId().equals(payer.getId())).findFirst().orElseThrow();
        assertThat(order.getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(stock()).isEqualTo(5);

        CreditWallet wallet = walletRepo.findById(payer.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(1);
        assertThat(wallet.getCommitted()).isZero();

        List<CreditPurchase> purchases = purchaseRepo.findAll().stream()
            .filter(p -> p.getUser().getId().equals(payer.getId())).toList();
        assertThat(purchases).hasSize(1);
        assertThat(purchases.get(0).getStatus()).isEqualTo(CreditPurchaseStatus.CANCELLED);
        assertThat(purchases.get(0).getMpPreferenceId()).isNull();
        assertThat(purchases.get(0).getInitPoint()).isNull();
    }

    @Test
    @DisplayName("createDirectCheckout(): una excepción inesperada del gateway (p. ej. timeout) también compensa y se propaga")
    void unexpectedGatewayExceptionAlsoCompensates() {
        seedCatalog(5);
        User payer = persistUser();
        when(paymentGateway.createCheckout(any())).thenThrow(new IllegalStateException("socket timeout"));

        assertThatThrownBy(() -> purchaseService.createDirectCheckout(payer.getId(), request()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("socket timeout");

        assertThat(stock()).isEqualTo(5);
        assertThat(orderRepo.findAll().stream().filter(o -> o.getUser().getId().equals(payer.getId())))
            .allMatch(o -> o.getEstado() == OrderEstado.CANCELADO);
    }

    // ─── compra cerrada antes de la fase 3 ──────────────────────────────────

    @Test
    @DisplayName("createDirectCheckout(): si la compra ya no está PENDING al guardar la preferencia (cerrada por otro camino), NO se resucita y no se devuelve el initPoint")
    void purchaseClosedBeforePhase3IsNotResurrected() {
        seedCatalog(5);
        User payer = persistUser();
        when(paymentGateway.createCheckout(any())).thenAnswer(inv -> {
            // Mientras Mercado Pago responde, la reconciliación expira la compra.
            purchaseService.expirePendingPurchase(purchaseIdOf(inv.getArgument(0)));
            return new CheckoutSession("pref-late", "https://mp.test/init-late");
        });

        assertThatThrownBy(() -> purchaseService.createDirectCheckout(payer.getId(), request()))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("checkout-purchase-closed"));

        CreditPurchase purchase = purchaseRepo.findAll().stream()
            .filter(p -> p.getUser().getId().equals(payer.getId())).findFirst().orElseThrow();
        assertThat(purchase.getStatus()).isEqualTo(CreditPurchaseStatus.EXPIRED);
        assertThat(purchase.getMpPreferenceId()).isNull();
        assertThat(purchase.getInitPoint()).isNull();
        assertThat(stock()).isEqualTo(5);
    }

    // ─── ventana de caída entre fase 1 y fase 3 ─────────────────────────────

    @Test
    @DisplayName("Caída entre fase 1 y fase 3: pedido PENDIENTE_PAGO + compra sin initPoint — resumeDirectCheckout responde 409 direct-checkout-not-resumable")
    void crashWindowLeavesAResumeThatFailsCleanly() {
        seedCatalog(5);
        User payer = persistUser();
        Order order = orderRepo.save(Order.builder()
            .user(payer).fecha(java.time.LocalDate.ofInstant(FIXED_NOW, ZONE))
            .pickupAt(FIXED_NOW.plus(60, ChronoUnit.MINUTES))
            .estado(OrderEstado.PENDIENTE_PAGO).creditTotal(2).build());
        purchaseRepo.save(CreditPurchase.builder()
            .user(payer).type(PurchaseType.DIRECT).order(order)
            .creditAmount(2).amountCents(3_000L).currency("ARS")
            .status(CreditPurchaseStatus.PENDING).build());

        assertThatThrownBy(() -> purchaseService.resumeDirectCheckout(payer.getId(), order.getId()))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("direct-checkout-not-resumable"));
    }

    // ─── Mercado Pago apagado: fail fast, sin tocar nada (B13.2) ────────────

    private void mercadoPagoIsDisabled() {
        doThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "mercadopago-disabled",
            "La integración con Mercado Pago no está configurada."))
            .when(paymentGateway).requireAvailable();
    }

    @Test
    @DisplayName("createDirectCheckout(): con Mercado Pago apagado responde 503 sin crear pedido ni compra ni tocar stock ni saldo")
    void directCheckoutWithMercadoPagoDisabledLeavesNoTrace() {
        seedCatalog(5);
        User payer = persistUser();
        seedWallet(payer.getId(), 1);
        mercadoPagoIsDisabled();

        assertThatThrownBy(() -> purchaseService.createDirectCheckout(payer.getId(), request()))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo("mercadopago-disabled");
                assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });

        assertThat(orderRepo.findAll().stream().filter(o -> o.getUser().getId().equals(payer.getId()))).isEmpty();
        assertThat(purchaseRepo.findAll().stream().filter(p -> p.getUser().getId().equals(payer.getId()))).isEmpty();
        assertThat(stock()).isEqualTo(5);
        CreditWallet wallet = walletRepo.findById(payer.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(1);
        assertThat(wallet.getCommitted()).isZero();
        verify(paymentGateway, never()).createCheckout(any());
    }

    @Test
    @DisplayName("createPurchase(PACK): con Mercado Pago apagado responde 503 sin crear ninguna compra")
    void packPurchaseWithMercadoPagoDisabledLeavesNoTrace() {
        seedCatalog(5);
        User buyer = persistUser();
        mercadoPagoIsDisabled();

        assertThatThrownBy(() -> purchaseService.createPurchase(buyer.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, packId, null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo("mercadopago-disabled");
                assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });

        assertThat(purchaseRepo.findAll().stream().filter(p -> p.getUser().getId().equals(buyer.getId()))).isEmpty();
        verify(paymentGateway, never()).createCheckout(any());
    }

    // ─── falla la fase 3 (guardar la preferencia): compensa (B13.2) ─────────

    @Test
    @DisplayName("createDirectCheckout(): si falla la fase 3 (no una caída) tras responder Mercado Pago, compensa — pedido CANCELADO, stock y saldo restaurados, compra CANCELLED — y lanza un error claro")
    void phase3FailureCompensatesAndThrowsAClearError() {
        seedCatalog(5);
        User payer = persistUser();
        seedWallet(payer.getId(), 1);
        // Falla REAL de la base en la fase 3: el preferenceId no entra en VARCHAR(100).
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession(TOO_LONG_PREFERENCE_ID, "https://mp.test/init-orphan"));

        assertThatThrownBy(() -> purchaseService.createDirectCheckout(payer.getId(), request()))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo("checkout-save-failed");
                assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.getCause()).isInstanceOf(org.springframework.dao.DataAccessException.class);
            });

        Order order = orderRepo.findAll().stream()
            .filter(o -> o.getUser().getId().equals(payer.getId())).findFirst().orElseThrow();
        assertThat(order.getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(stock()).isEqualTo(5);
        CreditWallet wallet = walletRepo.findById(payer.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(1);
        assertThat(wallet.getCommitted()).isZero();
        List<CreditPurchase> purchases = purchaseRepo.findAll().stream()
            .filter(p -> p.getUser().getId().equals(payer.getId())).toList();
        assertThat(purchases).hasSize(1);
        assertThat(purchases.get(0).getStatus()).isEqualTo(CreditPurchaseStatus.CANCELLED);
        assertThat(purchases.get(0).getInitPoint()).isNull();
    }

    @Test
    @DisplayName("createPurchase(PACK): si falla la fase 3, la compra queda CANCELLED y se lanza un error claro")
    void packPhase3FailureCancelsThePurchase() {
        seedCatalog(5);
        User buyer = persistUser();
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession(TOO_LONG_PREFERENCE_ID, "https://mp.test/init-orphan"));

        assertThatThrownBy(() -> purchaseService.createPurchase(buyer.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, packId, null, null)))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("checkout-save-failed"));

        List<CreditPurchase> purchases = purchaseRepo.findAll().stream()
            .filter(p -> p.getUser().getId().equals(buyer.getId())).toList();
        assertThat(purchases).hasSize(1);
        assertThat(purchases.get(0).getStatus()).isEqualTo(CreditPurchaseStatus.CANCELLED);
    }

    // ─── la compensación misma falla: se relanza el error ORIGINAL (B13.2) ──

    @Test
    @DisplayName("Si Mercado Pago falla y además falla la compensación, se relanza la excepción ORIGINAL con la de la compensación como suprimida")
    void failingCompensationAfterGatewayFailureKeepsTheOriginalError() {
        seedCatalog(5);
        User payer = persistUser();
        IllegalStateException gatewayFailure = new IllegalStateException("socket timeout");
        when(paymentGateway.createCheckout(any())).thenThrow(gatewayFailure);
        RuntimeException compensationFailure = new QueryTimeoutException("lock timeout");
        doThrow(compensationFailure).when(orderPlacementService).closeForPaymentFailure(any());

        assertThatThrownBy(() -> purchaseService.createDirectCheckout(payer.getId(), request()))
            .isSameAs(gatewayFailure)
            .hasSuppressedException(compensationFailure);
    }

    @Test
    @DisplayName("Si falla la fase 3 y además falla la compensación, el error claro conserva la causa original y la de la compensación como suprimida")
    void failingCompensationAfterPhase3FailureKeepsTheOriginalCause() {
        seedCatalog(5);
        User payer = persistUser();
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession(TOO_LONG_PREFERENCE_ID, "https://mp.test/init-orphan"));
        RuntimeException compensationFailure = new QueryTimeoutException("lock timeout");
        doThrow(compensationFailure).when(orderPlacementService).closeForPaymentFailure(any());

        assertThatThrownBy(() -> purchaseService.createDirectCheckout(payer.getId(), request()))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo("checkout-save-failed");
                assertThat(e.getCause()).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(e.getSuppressed()).containsExactly(compensationFailure);
            });
    }

    // ─── PACK: mismo split ──────────────────────────────────────────────────

    @Test
    @DisplayName("createPurchase(PACK): si Mercado Pago falla, la compra queda CANCELLED (no se revierte a la nada) y se propaga el mismo error")
    void packGatewayFailureMarksThePurchaseCancelled() {
        seedCatalog(5);
        User buyer = persistUser();
        when(paymentGateway.createCheckout(any())).thenThrow(new BusinessException(
            HttpStatus.BAD_GATEWAY, "mercadopago-checkout-failed", "No se pudo iniciar el pago con Mercado Pago."));

        assertThatThrownBy(() -> purchaseService.createPurchase(buyer.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, packId, null, null)))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("mercadopago-checkout-failed"));

        List<CreditPurchase> purchases = purchaseRepo.findAll().stream()
            .filter(p -> p.getUser().getId().equals(buyer.getId())).toList();
        assertThat(purchases).hasSize(1);
        assertThat(purchases.get(0).getStatus()).isEqualTo(CreditPurchaseStatus.CANCELLED);
    }

    @Test
    @DisplayName("createPurchase(PACK): camino feliz — persiste el preferenceId y devuelve el initPoint")
    void packHappyPathPersistsPreference() {
        seedCatalog(5);
        User buyer = persistUser();
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-pack", "https://mp.test/init-pack"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(buyer.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, packId, null, null));

        assertThat(dto.initPoint()).isEqualTo("https://mp.test/init-pack");
        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getStatus()).isEqualTo(CreditPurchaseStatus.PENDING);
        assertThat(purchase.getMpPreferenceId()).isEqualTo("pref-pack");
    }
}
