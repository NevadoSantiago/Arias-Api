package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.desserts.Dessert;
import com.arias.catalog.desserts.DessertRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.loyalty.LoyaltyCycleRepository;
import com.arias.loyalty.LoyaltyDto;
import com.arias.loyalty.LoyaltyLock;
import com.arias.loyalty.LoyaltyReward;
import com.arias.loyalty.LoyaltyRewardRepository;
import com.arias.loyalty.LoyaltyService;
import com.arias.loyalty.LoyaltyTestSupport;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Redeeming the loyalty dessert on a lunch order (place and direct checkout) and giving it back on cancel. */
@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true", "arias.loyalty.enabled=true"})
@Transactional
@Import(OrderRewardDessertTest.FixedClockConfig.class)
class OrderRewardDessertTest {

    // 2026-03-10 10:40 ART, inside the default ordering window.
    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:40:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final AtomicLong PHONE_SEQ = new AtomicLong();

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired private OrderPlacementService service;
    @Autowired private LoyaltyService loyaltyService;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private DessertRepository dessertRepo;
    @Autowired private CreditWalletRepository walletRepo;
    @Autowired private LoyaltyCycleRepository cycleRepo;
    @Autowired private LoyaltyRewardRepository rewardRepo;
    @Autowired private OrderRewardItemRepository rewardItemRepo;
    @Autowired private EntityManager em;
    @MockitoSpyBean private LoyaltyLock lock;

    // -- fixtures -------------------------------------------------------------

    private User user() {
        return userRepo.save(User.builder()
            .email("b2c-" + System.nanoTime() + "@test.arias.com").role(Role.EMPLOYEE).active(true)
            .emailVerifiedAt(Instant.now()).phone("+549" + (1133440100L + PHONE_SEQ.incrementAndGet()))
            .nickname("Apodo-" + System.nanoTime()).build());
    }

    private Dish dish(int stock) {
        Category category = categoryRepo.save(Category.builder().nombre("Cat-" + System.nanoTime())
            .ordenDisplay(0).enabled(true).creditCost(1).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder().nombre("sec-" + System.nanoTime())
            .ordenDisplay(0).enabled(true).build());
        return dishRepo.save(Dish.builder().nombre("Plato-" + System.nanoTime()).category(category)
            .menuSection(section).enabled(true).especial(false).stockDiarioDefault(stock).stockActual(stock).build());
    }

    private void wallet(User user, int available) {
        walletRepo.saveAndFlush(CreditWallet.builder().userId(user.getId()).available(available).committed(0).build());
    }

    private Dessert dessert(int stock) {
        return LoyaltyTestSupport.dessert(dessertRepo, "Flan-" + System.nanoTime(), stock);
    }

    private LoyaltyReward reward(User user, Duration validFromNow) {
        return LoyaltyTestSupport.dessertReward(cycleRepo, rewardRepo, user,
            FIXED_NOW.minus(Duration.ofDays(1)), FIXED_NOW.plus(validFromNow));
    }

    private static Instant pickup() {
        return FIXED_NOW.plus(90, ChronoUnit.MINUTES);
    }

    private static PlaceOrderV2Request request(Dish dish, Long dessertId) {
        return new PlaceOrderV2Request(
            List.of(new PlaceOrderV2Request.OrderItemRequest(dish.getId(), null, null)), pickup(), null, dessertId);
    }

    private int dessertStock(Dessert d) {
        em.flush();
        em.clear();
        return dessertRepo.findById(d.getId()).orElseThrow().getStockActual();
    }

    private LoyaltyReward reload(LoyaltyReward r) {
        em.flush();
        em.clear();
        return rewardRepo.findById(r.getId()).orElseThrow();
    }

    private void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", code);
    }

    // -- place ----------------------------------------------------------------

    @Test
    @DisplayName("place(): redeems the pending dessert as an extra line without touching credits")
    void placeRedeemsDessert() {
        User u = user();
        wallet(u, 10);
        Dish dish = dish(5);
        Dessert flan = dessert(3);
        LoyaltyReward reward = reward(u, Duration.ofDays(5));

        OrderDto placed = service.place(u.getId(), request(dish, flan.getId()));

        assertThat(placed.creditTotal()).isEqualTo(1);
        assertThat(placed.items()).hasSize(1);
        assertThat(placed.rewardItems()).singleElement().satisfies(r -> {
            assertThat(r.dessertId()).isEqualTo(flan.getId());
            assertThat(r.nombre()).isEqualTo(flan.getNombre());
        });
        assertThat(dessertStock(flan)).isEqualTo(2);
        LoyaltyReward used = reload(reward);
        assertThat(used.getUsedAt()).isEqualTo(FIXED_NOW);
        assertThat(used.getUsedOrderId()).isEqualTo(placed.id());
        CreditWallet w = walletRepo.findByIdForUpdate(u.getId()).orElseThrow();
        assertThat(w.getAvailable()).isEqualTo(9);
        assertThat(w.getCommitted()).isEqualTo(1);
        assertThat(rewardItemRepo.findByOrderId(placed.id())).singleElement().satisfies(i -> {
            assertThat(i.getDessertNombre()).isEqualTo(flan.getNombre());
            assertThat(i.getReward().getId()).isEqualTo(reward.getId());
            assertThat(i.getDessert().getId()).isEqualTo(flan.getId());
        });
    }

    @Test
    @DisplayName("place() without a dessert returns an empty rewardItems list")
    void placeWithoutDessert() {
        User u = user();
        wallet(u, 10);

        OrderDto placed = service.place(u.getId(), request(dish(5), null));

        assertThat(placed.rewardItems()).isEmpty();
    }

    @Test
    @DisplayName("place(): takes the dessert reward that expires first")
    void usesOldestExpiring() {
        User u = user();
        wallet(u, 10);
        LoyaltyReward later = reward(u, Duration.ofDays(6));
        LoyaltyReward sooner = LoyaltyTestSupport.dessertReward(cycleRepo, rewardRepo, u,
            FIXED_NOW.minus(Duration.ofDays(3)), FIXED_NOW.plus(Duration.ofDays(2)));

        service.place(u.getId(), request(dish(5), dessert(3).getId()));

        assertThat(reload(sooner).getUsedAt()).isNotNull();
        assertThat(reload(later).getUsedAt()).isNull();
    }

    @Test
    @DisplayName("place(): the redemption runs under the per-user loyalty lock")
    void takesTheLock() {
        User u = user();
        wallet(u, 10);
        reward(u, Duration.ofDays(5));

        service.place(u.getId(), request(dish(5), dessert(3).getId()));

        Mockito.verify(lock, Mockito.atLeastOnce()).lock(u.getId());
    }

    @Test
    @DisplayName("place(): a user without an unused, unexpired dessert reward gets 409 no-pending-dessert")
    void noPendingDessert() {
        User none = user();
        wallet(none, 10);
        Dessert flan = dessert(3);
        assertCode(() -> service.place(none.getId(), request(dish(5), flan.getId())), "no-pending-dessert");

        User expired = user();
        wallet(expired, 10);
        LoyaltyTestSupport.dessertReward(cycleRepo, rewardRepo, expired,
            FIXED_NOW.minus(Duration.ofDays(9)), FIXED_NOW.minus(Duration.ofDays(2)));
        assertCode(() -> service.place(expired.getId(), request(dish(5), flan.getId())), "no-pending-dessert");

        User used = user();
        wallet(used, 10);
        LoyaltyReward r = reward(used, Duration.ofDays(5));
        r.setUsedAt(FIXED_NOW.minusSeconds(5));
        rewardRepo.saveAndFlush(r);
        assertCode(() -> service.place(used.getId(), request(dish(5), flan.getId())), "no-pending-dessert");
        assertThat(dessertStock(flan)).isEqualTo(3);
    }

    @Test
    @DisplayName("place(): an unknown, disabled or deleted dessert is 404 dessert-not-found")
    void dessertNotFound() {
        User u = user();
        wallet(u, 10);
        reward(u, Duration.ofDays(5));
        Dessert disabled = dessert(3);
        disabled.setEnabled(false);
        dessertRepo.saveAndFlush(disabled);
        Dessert deleted = dessert(3);
        deleted.setDeletedAt(FIXED_NOW);
        dessertRepo.saveAndFlush(deleted);

        assertCode(() -> service.place(u.getId(), request(dish(5), 999_999_999L)), "dessert-not-found");
        assertCode(() -> service.place(u.getId(), request(dish(5), disabled.getId())), "dessert-not-found");
        assertCode(() -> service.place(u.getId(), request(dish(5), deleted.getId())), "dessert-not-found");
    }

    @Test
    @DisplayName("place(): a dessert without stock is 409 dessert-out-of-stock and the reward stays unused")
    void dessertOutOfStock() {
        User u = user();
        wallet(u, 10);
        LoyaltyReward reward = reward(u, Duration.ofDays(5));
        Dessert flan = dessert(0);

        assertCode(() -> service.place(u.getId(), request(dish(5), flan.getId())), "dessert-out-of-stock");

        assertThat(reload(reward).getUsedAt()).isNull();
        assertThat(dessertStock(flan)).isZero();
    }

    @Test
    @DisplayName("place(): the last unit goes to the first redeemer, the next one gets dessert-out-of-stock")
    void lastUnitGoesToOne() {
        Dessert flan = dessert(1);
        User a = user();
        wallet(a, 10);
        reward(a, Duration.ofDays(5));
        User b = user();
        wallet(b, 10);
        reward(b, Duration.ofDays(5));

        service.place(a.getId(), request(dish(5), flan.getId()));

        assertCode(() -> service.place(b.getId(), request(dish(5), flan.getId())), "dessert-out-of-stock");
        assertThat(dessertStock(flan)).isZero();
    }

    // -- direct checkout --------------------------------------------------------

    @Test
    @DisplayName("placeAwaitingPayment(): the dessert is reserved on the unpaid order too")
    void awaitingPaymentRedeems() {
        User u = user();
        Dessert flan = dessert(3);
        LoyaltyReward reward = reward(u, Duration.ofDays(5));

        Order order = service.placeAwaitingPayment(u.getId(), request(dish(5), flan.getId()));

        assertThat(order.getEstado()).isEqualTo(OrderEstado.PENDIENTE_PAGO);
        assertThat(order.getRewardItems()).hasSize(1);
        assertThat(dessertStock(flan)).isEqualTo(2);
        assertThat(reload(reward).getUsedOrderId()).isEqualTo(order.getId());
    }

    @Test
    @DisplayName("closeForPaymentFailure() and closeAtCutoff() give the dessert back")
    void paymentFailureReturnsDessert() {
        for (boolean cutoff : new boolean[] {false, true}) {
            User u = user();
            Dessert flan = dessert(3);
            LoyaltyReward reward = reward(u, Duration.ofDays(5));
            Order order = service.placeAwaitingPayment(u.getId(), request(dish(5), flan.getId()));
            em.flush();

            if (cutoff) {
                service.closeAtCutoff(order.getId());
            } else {
                service.closeForPaymentFailure(order.getId());
            }

            assertThat(dessertStock(flan)).isEqualTo(3);
            LoyaltyReward back = reload(reward);
            assertThat(back.getUsedAt()).isNull();
            assertThat(back.getUsedOrderId()).isNull();
        }
    }

    // -- cancellation -------------------------------------------------------------

    @Test
    @DisplayName("cancel(): the dessert goes back to stock and the reward is returned; the history line stays")
    void cancelReturnsDessert() {
        User u = user();
        wallet(u, 10);
        Dessert flan = dessert(3);
        LoyaltyReward reward = reward(u, Duration.ofDays(5));
        OrderDto placed = service.place(u.getId(), request(dish(5), flan.getId()));
        em.flush();
        em.clear();

        service.cancel(u.getId(), placed.id());

        assertThat(dessertStock(flan)).isEqualTo(3);
        LoyaltyReward back = reload(reward);
        assertThat(back.getUsedAt()).isNull();
        assertThat(back.getUsedOrderId()).isNull();
        assertThat(rewardItemRepo.findByOrderId(placed.id())).hasSize(1);
        LoyaltyDto view = loyaltyService.view(u.getId());
        assertThat(view.pendingDessert()).isNotNull();
        assertThat(view.pendingDessert().rewardId()).isEqualTo(reward.getId());
    }

    @Test
    @DisplayName("a returned reward can be redeemed again on another order")
    void redeemAgainAfterCancel() {
        User u = user();
        wallet(u, 10);
        Dessert flan = dessert(3);
        LoyaltyReward reward = reward(u, Duration.ofDays(5));
        OrderDto first = service.place(u.getId(), request(dish(5), flan.getId()));
        em.flush();
        em.clear();
        service.cancel(u.getId(), first.id());

        OrderDto second = service.place(u.getId(), request(dish(5), flan.getId()));

        assertThat(second.rewardItems()).hasSize(1);
        assertThat(reload(reward).getUsedOrderId()).isEqualTo(second.id());
        assertThat(dessertStock(flan)).isEqualTo(2);
    }

    @Test
    @DisplayName("cancel(): a reward already past its expiry is returned but shows as expired")
    void cancelAfterExpiry() {
        User u = user();
        wallet(u, 10);
        Dessert flan = dessert(3);
        LoyaltyReward reward = reward(u, Duration.ofDays(5));
        OrderDto placed = service.place(u.getId(), request(dish(5), flan.getId()));
        LoyaltyReward r = reload(reward);
        r.setExpiresAt(FIXED_NOW.minus(Duration.ofHours(1)));
        rewardRepo.saveAndFlush(r);
        em.clear();

        service.cancel(u.getId(), placed.id());

        assertThat(reload(reward).getUsedAt()).isNull();
        assertThat(dessertStock(flan)).isEqualTo(3);
        LoyaltyDto view = loyaltyService.view(u.getId());
        assertThat(view.pendingDessert()).isNull();
    }

    @Test
    @DisplayName("removeItem() of the last lunch cancels the order and returns the dessert")
    void removingLastItemReturnsDessert() {
        User u = user();
        wallet(u, 10);
        Dessert flan = dessert(3);
        LoyaltyReward reward = reward(u, Duration.ofDays(5));
        OrderDto placed = service.place(u.getId(), request(dish(5), flan.getId()));
        em.flush();
        em.clear();

        service.removeItem(u.getId(), placed.id(), placed.items().get(0).id());

        assertThat(dessertStock(flan)).isEqualTo(3);
        assertThat(reload(reward).getUsedAt()).isNull();
    }

    @Test
    @DisplayName("an order cancelled twice never returns the dessert twice")
    void idempotentReturn() {
        User u = user();
        wallet(u, 10);
        Dessert flan = dessert(3);
        reward(u, Duration.ofDays(5));
        OrderDto placed = service.place(u.getId(), request(dish(5), flan.getId()));
        em.flush();
        em.clear();
        service.cancel(u.getId(), placed.id());

        assertCode(() -> service.cancel(u.getId(), placed.id()), "order-locked");

        assertThat(dessertStock(flan)).isEqualTo(3);
    }
}
