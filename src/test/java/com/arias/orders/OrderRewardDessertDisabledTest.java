package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** With the loyalty feature off a dessert redemption is refused and plain orders are unaffected. */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(OrderRewardDessertDisabledTest.FixedClockConfig.class)
class OrderRewardDessertDisabledTest {

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:40:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired private OrderPlacementService service;
    @Autowired private UserRepository userRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private CreditWalletRepository walletRepo;

    private PlaceOrderV2Request request(Long dessertId) {
        Category category = categoryRepo.save(Category.builder().nombre("Cat-" + System.nanoTime())
            .ordenDisplay(0).enabled(true).creditCost(1).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder().nombre("sec-" + System.nanoTime())
            .ordenDisplay(0).enabled(true).build());
        Dish dish = dishRepo.save(Dish.builder().nombre("Plato-" + System.nanoTime()).category(category)
            .menuSection(section).enabled(true).especial(false).stockDiarioDefault(5).stockActual(5).build());
        return new PlaceOrderV2Request(
            List.of(new PlaceOrderV2Request.OrderItemRequest(dish.getId(), null, null)),
            FIXED_NOW.plus(90, ChronoUnit.MINUTES), null, dessertId);
    }

    private User user() {
        User u = userRepo.save(User.builder().email("off-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE).active(true).emailVerifiedAt(Instant.now())
            .phone("+549" + (1155660100L + System.nanoTime() % 1_000_000)).nickname("Apodo-" + System.nanoTime())
            .build());
        walletRepo.saveAndFlush(CreditWallet.builder().userId(u.getId()).available(5).committed(0).build());
        return u;
    }

    @Test
    @DisplayName("place() with a dessert while the feature is off is 409 loyalty-disabled")
    void refusedWhenDisabled() {
        User u = user();

        assertThatThrownBy(() -> service.place(u.getId(), request(1L)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "loyalty-disabled");
    }

    @Test
    @DisplayName("place() without a dessert is unaffected and has no reward items")
    void plainOrderUnaffected() {
        User u = user();

        OrderDto placed = service.place(u.getId(), request(null));

        assertThat(placed.rewardItems()).isEmpty();
    }
}
