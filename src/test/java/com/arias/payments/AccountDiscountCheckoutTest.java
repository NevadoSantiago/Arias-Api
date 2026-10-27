package com.arias.payments;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.discount.AccountDiscount;
import com.arias.credits.discount.AccountDiscountRepository;
import com.arias.credits.discount.AccountDiscountService;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.credits.packs.CreditPackType;
import com.arias.orders.PlaceOrderV2Request;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one-time account discount at checkout: priced into amountCents (rounded down to a whole
 * peso), reserved while the purchase is PENDING, consumed on approval and released when the
 * purchase closes without payment.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(AccountDiscountCheckoutTest.FixedClockConfig.class)
class AccountDiscountCheckoutTest {

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:40:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final AtomicLong PHONE_SEQ = new AtomicLong(2_000);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired private CreditPurchaseService purchaseService;
    @Autowired private CreditPurchaseRepository purchaseRepo;
    @Autowired private CreditPackRepository packRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private CreditWalletRepository walletRepo;
    @Autowired private AccountDiscountService discountService;
    @Autowired private AccountDiscountRepository discountRepo;
    @Autowired private EntityManager entityManager;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepo.save(User.builder()
            .email("acct-disc-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone("+549" + (1155990100L + PHONE_SEQ.incrementAndGet()))
            .nickname("Apodo-" + System.nanoTime())
            .build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-disc", "https://mp.test/init-disc"));
    }

    // ─── fixtures ───────────────────────────────────────────────────────────

    private void givenDiscount() {
        assertThat(discountService.grantIfAbsent(user, 10, "test")).isTrue();
        flushAndClear();
    }

    private CreditPack pack(int creditAmount, long priceCents, CreditPackType type) {
        return packRepo.save(CreditPack.builder()
            .code(type == CreditPackType.INDIVIDUAL ? "DAY" : "P-" + UUID.randomUUID().toString().substring(0, 8))
            .packType(type).nombre("Pack").creditAmount(creditAmount).priceCents(priceCents)
            .discountPercent(0).ordenDisplay(0).enabled(true).build());
    }

    private UUID buyPack(CreditPack pack, Integer quantity) {
        UUID id = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, quantity)).purchaseId();
        flushAndClear();
        return id;
    }

    private DirectCheckoutDto buyDirect() {
        pack(2, 3_000L, CreditPackType.INDIVIDUAL);
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(2).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        Dish dish = dishRepo.save(Dish.builder()
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

    private void apply(UUID purchaseId, String paymentId, PaymentStatus status) {
        CreditPurchase p = purchaseRepo.findById(purchaseId).orElseThrow();
        purchaseService.applySnapshot(new PaymentSnapshot(paymentId, status, "detail",
            p.getAmountCents(), "ARS", purchaseId.toString(), 0L));
        flushAndClear();
    }

    private AccountDiscount discount() {
        return discountRepo.findByUserId(user.getId()).orElseThrow();
    }

    private boolean usableNow() {
        return discountService.findActive(user.getId(), FIXED_NOW).isPresent();
    }

    private CheckoutRequest lastCheckoutRequest() {
        ArgumentCaptor<CheckoutRequest> captor = ArgumentCaptor.forClass(CheckoutRequest.class);
        verify(paymentGateway, atLeastOnce()).createCheckout(captor.capture());
        return captor.getValue();
    }

    // ─── pricing ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("discountedAmountCents rounds down to a whole peso")
    void roundsDownToWholePeso() {
        assertThat(AccountDiscountService.discountedAmountCents(10_000L, 10)).isEqualTo(9_000L);
        assertThat(AccountDiscountService.discountedAmountCents(12_350L, 10)).isEqualTo(11_100L);
        assertThat(AccountDiscountService.discountedAmountCents(100L, 10)).isZero();
    }

    @Test
    @DisplayName("PACK: the discounted amount is stored with list amount and percent, and Mercado Pago charges exactly that")
    void packIsDiscounted() {
        givenDiscount();
        CreditPack pack = pack(10, 12_350L, CreditPackType.OTRO);

        UUID id = buyPack(pack, null);

        CreditPurchase p = purchaseRepo.findById(id).orElseThrow();
        assertThat(p.getAmountCents()).isEqualTo(11_100L); // 12350 * 0.9 = 11115 -> 11100
        assertThat(p.getListAmountCents()).isEqualTo(12_350L);
        assertThat(p.getDiscountPercent()).isEqualTo(10);
        assertThat(p.getCreditAmount()).isEqualTo(10);
        CheckoutRequest req = lastCheckoutRequest();
        assertThat(req.quantity()).isEqualTo(1);
        assertThat(req.unitPriceCents()).isEqualTo(11_100L);
        assertThat(req.title()).contains("10%");
    }

    @Test
    @DisplayName("sueltos (INDIVIDUAL pack x quantity): discount applies to the whole amount, credits unchanged")
    void sueltosAreDiscounted() {
        givenDiscount();
        CreditPack individual = pack(2, 3_000L, CreditPackType.INDIVIDUAL);

        UUID id = buyPack(individual, 3);

        CreditPurchase p = purchaseRepo.findById(id).orElseThrow();
        assertThat(p.getCreditAmount()).isEqualTo(6);
        assertThat(p.getListAmountCents()).isEqualTo(9_000L);
        assertThat(p.getAmountCents()).isEqualTo(8_100L);
        CheckoutRequest req = lastCheckoutRequest();
        assertThat(req.quantity()).isEqualTo(1);
        assertThat(req.unitPriceCents()).isEqualTo(8_100L);
    }

    @Test
    @DisplayName("DIRECT: the discount applies to the amount Mercado Pago charges")
    void directIsDiscounted() {
        givenDiscount();

        DirectCheckoutDto dto = buyDirect();

        CreditPurchase p = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(p.getListAmountCents()).isEqualTo(3_000L);
        assertThat(p.getAmountCents()).isEqualTo(2_700L);
        assertThat(p.getDiscountPercent()).isEqualTo(10);
        assertThat(lastCheckoutRequest().unitPriceCents()).isEqualTo(2_700L);
        assertThat(discount().getReservedPurchaseId()).isEqualTo(dto.purchaseId());
    }

    @Test
    @DisplayName("resumeDirectCheckout keeps the original discounted purchase")
    void resumeKeepsDiscountedPurchase() {
        givenDiscount();
        DirectCheckoutDto dto = buyDirect();

        DirectCheckoutDto resumed = purchaseService.resumeDirectCheckout(user.getId(), dto.orderId());

        assertThat(resumed.purchaseId()).isEqualTo(dto.purchaseId());
        assertThat(purchaseRepo.findById(dto.purchaseId()).orElseThrow().getAmountCents()).isEqualTo(2_700L);
    }

    @Test
    @DisplayName("without a discount the price is untouched and no list amount is stored")
    void noDiscountNoChange() {
        CreditPack pack = pack(10, 12_350L, CreditPackType.OTRO);

        UUID id = buyPack(pack, null);

        CreditPurchase p = purchaseRepo.findById(id).orElseThrow();
        assertThat(p.getAmountCents()).isEqualTo(12_350L);
        assertThat(p.getListAmountCents()).isNull();
        assertThat(p.getDiscountPercent()).isNull();
    }

    @Test
    @DisplayName("an expired discount is not applied")
    void expiredDiscountIsIgnored() {
        givenDiscount();
        discount().setExpiresAt(FIXED_NOW.minusSeconds(1));
        flushAndClear();

        UUID id = buyPack(pack(10, 10_000L, CreditPackType.OTRO), null);

        assertThat(purchaseRepo.findById(id).orElseThrow().getAmountCents()).isEqualTo(10_000L);
        assertThat(discount().getReservedPurchaseId()).isNull();
    }

    @Test
    @DisplayName("a discount that would bring the price below one peso is not applied")
    void tinyAmountSkipsDiscount() {
        givenDiscount();

        UUID id = buyPack(pack(1, 100L, CreditPackType.OTRO), null);

        assertThat(purchaseRepo.findById(id).orElseThrow().getAmountCents()).isEqualTo(100L);
        assertThat(usableNow()).isTrue();
    }

    // ─── reservation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a PENDING purchase reserves the discount: hidden from the wallet until released")
    void reservedWhilePending() {
        givenDiscount();
        UUID first = buyPack(pack(10, 10_000L, CreditPackType.OTRO), null);

        assertThat(discount().getReservedPurchaseId()).isEqualTo(first);
        assertThat(discount().getConsumedAt()).isNull();
        assertThat(usableNow()).isFalse();
    }

    @Test
    @DisplayName("a second purchase made while the discount is reserved pays the full price")
    void secondPurchaseWhileReservedIsFullPrice() {
        givenDiscount();
        CreditPack pack = pack(10, 10_000L, CreditPackType.OTRO);
        UUID first = buyPack(pack, null);

        UUID second = buyPack(pack, null);

        assertThat(purchaseRepo.findById(second).orElseThrow().getAmountCents()).isEqualTo(10_000L);
        assertThat(discount().getReservedPurchaseId()).isEqualTo(first);
    }

    // ─── consume / release ──────────────────────────────────────────────────

    @Test
    @DisplayName("APPROVED consumes the discount")
    void approvedConsumes() {
        givenDiscount();
        UUID id = buyPack(pack(10, 10_000L, CreditPackType.OTRO), null);

        apply(id, "mp-1", PaymentStatus.APPROVED);

        AccountDiscount d = discount();
        assertThat(d.getConsumedAt()).isEqualTo(FIXED_NOW);
        assertThat(d.getConsumedPurchaseId()).isEqualTo(id);
        assertThat(d.getReservedPurchaseId()).isNull();
        assertThat(usableNow()).isFalse();
        assertThat(walletRepo.findById(user.getId()).orElseThrow().getAvailable()).isEqualTo(10);
    }

    @Test
    @DisplayName("REJECTED releases the discount so it can be used again")
    void rejectedReleases() {
        givenDiscount();
        UUID id = buyPack(pack(10, 10_000L, CreditPackType.OTRO), null);

        apply(id, "mp-1", PaymentStatus.REJECTED);

        assertThat(discount().getReservedPurchaseId()).isNull();
        assertThat(discount().getConsumedAt()).isNull();
        assertThat(usableNow()).isTrue();
    }

    @Test
    @DisplayName("CANCELLED releases the discount")
    void cancelledReleases() {
        givenDiscount();
        UUID id = buyPack(pack(10, 10_000L, CreditPackType.OTRO), null);

        apply(id, "mp-1", PaymentStatus.CANCELLED);

        assertThat(usableNow()).isTrue();
    }

    @Test
    @DisplayName("EXPIRED releases the discount")
    void expiredReleases() {
        givenDiscount();
        UUID id = buyPack(pack(10, 10_000L, CreditPackType.OTRO), null);

        purchaseService.expirePendingPurchase(id);
        flushAndClear();

        assertThat(usableNow()).isTrue();
    }

    @Test
    @DisplayName("a Mercado Pago failure while creating the checkout releases the discount")
    void gatewayFailureReleases() {
        givenDiscount();
        CreditPack pack = pack(10, 10_000L, CreditPackType.OTRO);
        when(paymentGateway.createCheckout(any()))
            .thenThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "mp-down", "down"));

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null)))
            .isInstanceOf(BusinessException.class);
        flushAndClear();

        assertThat(discount().getReservedPurchaseId()).isNull();
        assertThat(usableNow()).isTrue();
    }

    @Test
    @DisplayName("an approval that arrives after the purchase was rejected still consumes the discount")
    void lateApprovalConsumes() {
        givenDiscount();
        UUID id = buyPack(pack(10, 10_000L, CreditPackType.OTRO), null);
        apply(id, "mp-1", PaymentStatus.REJECTED);
        assertThat(usableNow()).isTrue();

        apply(id, "mp-2", PaymentStatus.APPROVED);

        assertThat(discount().getConsumedPurchaseId()).isEqualTo(id);
        assertThat(usableNow()).isFalse();
    }

    @Test
    @DisplayName("an approval after the discount expired while reserved still consumes it")
    void approvalAfterExpiryConsumes() {
        givenDiscount();
        UUID id = buyPack(pack(10, 10_000L, CreditPackType.OTRO), null);
        discount().setExpiresAt(FIXED_NOW.minusSeconds(60));
        flushAndClear();

        apply(id, "mp-1", PaymentStatus.APPROVED);

        assertThat(discount().getConsumedAt()).isNotNull();
    }

    @Test
    @DisplayName("release and consume are idempotent, and a stale release leaves another reservation alone")
    void releaseAndConsumeAreIdempotent() {
        givenDiscount();
        UUID id = buyPack(pack(10, 10_000L, CreditPackType.OTRO), null);

        // a stale release must not touch a reservation held by another purchase
        discountService.release(user.getId(), UUID.randomUUID());
        flushAndClear();
        assertThat(discount().getReservedPurchaseId()).isEqualTo(id);

        discountService.release(user.getId(), id);
        discountService.release(user.getId(), id);
        flushAndClear();
        assertThat(discount().getReservedPurchaseId()).isNull();

        discountService.consume(user.getId(), id);
        flushAndClear();
        Instant firstConsumedAt = discount().getConsumedAt();
        discountService.consume(user.getId(), UUID.randomUUID());
        flushAndClear();
        assertThat(discount().getConsumedPurchaseId()).isEqualTo(id);
        assertThat(discount().getConsumedAt()).isEqualTo(firstConsumedAt);
    }
}
