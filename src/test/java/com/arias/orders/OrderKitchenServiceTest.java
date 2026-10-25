package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditMovement;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.MovementType;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kitchen state transitions of B2C {@link Order}s: CONFIRMADO -> COMANDADO -> ENTREGADO, with undo. */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(OrderKitchenServiceTest.FixedClockConfig.class)
class OrderKitchenServiceTest {

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:00:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired private OrderKitchenService service;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private MenuSectionRepository menuSectionRepo;
    @Autowired private DishRepository dishRepo;
    @Autowired private CreditWalletRepository walletRepo;
    @Autowired private CreditMovementRepository movementRepo;

    private Order persistOrder(OrderEstado estado) {
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(2).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        Dish dish = dishRepo.save(Dish.builder()
            .nombre("Plato-" + System.nanoTime()).category(category).menuSection(section)
            .enabled(true).especial(false).stockDiarioDefault(50).stockActual(50).build());
        User user = userRepo.save(User.builder()
            .email("k-" + System.nanoTime() + "@test.arias.com").role(Role.EMPLOYEE)
            .nickname("Coty").active(true).build());
        Instant pickupAt = FIXED_NOW.plusSeconds(3600);
        Order order = Order.builder()
            .user(user).fecha(LocalDate.ofInstant(pickupAt, ZONE)).pickupAt(pickupAt)
            .estado(estado).creditTotal(2).build();
        order.addItem(OrderItem.builder().dish(dish).category(category).dishNombre(dish.getNombre())
            .dishCategoria(category.getNombre()).creditCost(2).build());
        return orderRepo.save(order);
    }

    private void seedWallet(Order order, int available, int committed) {
        walletRepo.saveAndFlush(CreditWallet.builder()
            .userId(order.getUser().getId()).available(available).committed(committed).build());
    }

    @Test
    @DisplayName("markConfirmado(): PENDIENTE pasa a CONFIRMADO, consume los creditos comprometidos y registra confirmedAt")
    void markConfirmadoConsumesCredits() {
        Order o = persistOrder(OrderEstado.PENDIENTE);
        seedWallet(o, 3, 2);

        Order result = service.markConfirmado(List.of(o.getId())).get(0);

        assertThat(result.getEstado()).isEqualTo(OrderEstado.CONFIRMADO);
        assertThat(result.getConfirmedAt()).isEqualTo(FIXED_NOW);
        CreditWallet wallet = walletRepo.findByIdForUpdate(o.getUser().getId()).orElseThrow();
        assertThat(wallet.getCommitted()).isZero();
        assertThat(wallet.getAvailable()).isEqualTo(3);
        List<CreditMovement> movements = movementRepo.findByUserIdOrderByCreatedAtDesc(o.getUser().getId());
        assertThat(movements).singleElement().satisfies(m -> {
            assertThat(m.getType()).isEqualTo(MovementType.CONSUME);
            assertThat(m.getDeltaCommitted()).isEqualTo(-2);
            assertThat(m.getDeltaAvailable()).isZero();
            assertThat(m.getOrderId()).isEqualTo(o.getId());
        });
    }

    @Test
    @DisplayName("markConfirmado(): rechaza CONFIRMADO, COMANDADO, CANCELADO y PENDIENTE_PAGO con 409")
    void markConfirmadoRejectsOtherStates() {
        for (OrderEstado estado : List.of(OrderEstado.CONFIRMADO, OrderEstado.COMANDADO,
                OrderEstado.CANCELADO, OrderEstado.PENDIENTE_PAGO)) {
            Order o = persistOrder(estado);
            assertThatThrownBy(() -> service.markConfirmado(List.of(o.getId())))
                .as("estado %s", estado)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("order-invalid-transition");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });
        }
    }

    @Test
    @DisplayName("markConfirmado(): lote todo-o-nada, un pedido invalido no consume ni confirma a los validos")
    void markConfirmadoBatchIsAllOrNothing() {
        Order ok = persistOrder(OrderEstado.PENDIENTE);
        seedWallet(ok, 0, 2);
        Order bad = persistOrder(OrderEstado.COMANDADO);

        assertThatThrownBy(() -> service.markConfirmado(List.of(ok.getId(), bad.getId())))
            .isInstanceOf(BusinessException.class);

        assertThat(ok.getEstado()).isEqualTo(OrderEstado.PENDIENTE);
        assertThat(ok.getConfirmedAt()).isNull();
        assertThat(walletRepo.findByIdForUpdate(ok.getUser().getId()).orElseThrow().getCommitted()).isEqualTo(2);
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(ok.getUser().getId())).isEmpty();
    }

    @Test
    @DisplayName("markConfirmado(): lote valido confirma todos")
    void markConfirmadoBatch() {
        Order a = persistOrder(OrderEstado.PENDIENTE);
        Order b = persistOrder(OrderEstado.PENDIENTE);
        seedWallet(a, 0, 2);
        seedWallet(b, 0, 2);

        assertThat(service.markConfirmado(List.of(a.getId(), b.getId())))
            .hasSize(2)
            .allMatch(o -> o.getEstado() == OrderEstado.CONFIRMADO);
    }

    @Test
    @DisplayName("markConfirmado(): lista vacia da 400 order-ids-required")
    void markConfirmadoEmptyList() {
        assertThatThrownBy(() -> service.markConfirmado(List.of()))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo("order-ids-required");
                assertThat(e.getStatus().value()).isEqualTo(400);
            });
    }

    @Test
    @DisplayName("markConfirmado(): id inexistente da 404 order-not-found")
    void markConfirmadoUnknownId() {
        assertThatThrownBy(() -> service.markConfirmado(List.of(-1L)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo("order-not-found");
                assertThat(e.getStatus().value()).isEqualTo(404);
            });
    }

    @Test
    @DisplayName("markComandado(): CONFIRMADO pasa a COMANDADO y registra comandadoAt")
    void markComandadoFromConfirmado() {
        Order o = persistOrder(OrderEstado.CONFIRMADO);

        List<Order> result = service.markComandado(List.of(o.getId()));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEstado()).isEqualTo(OrderEstado.COMANDADO);
        assertThat(result.get(0).getComandadoAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("markComandado(): rechaza un pedido que no esta CONFIRMADO con 409 order-invalid-transition")
    void markComandadoRejectsOtherStates() {
        Order o = persistOrder(OrderEstado.PENDIENTE);

        assertThatThrownBy(() -> service.markComandado(List.of(o.getId())))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo("order-invalid-transition");
                assertThat(e.getStatus().value()).isEqualTo(409);
            });
    }

    @Test
    @DisplayName("markComandado(): id inexistente da 404 order-not-found")
    void markComandadoUnknownId() {
        assertThatThrownBy(() -> service.markComandado(List.of(-1L)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo("order-not-found");
                assertThat(e.getStatus().value()).isEqualTo(404);
            });
    }

    @Test
    @DisplayName("markComandado(): lote todo-o-nada, un pedido invalido no modifica a los validos")
    void markComandadoBatchIsAllOrNothing() {
        Order ok = persistOrder(OrderEstado.CONFIRMADO);
        Order bad = persistOrder(OrderEstado.ENTREGADO);

        assertThatThrownBy(() -> service.markComandado(List.of(ok.getId(), bad.getId())))
            .isInstanceOf(BusinessException.class);

        assertThat(ok.getEstado()).isEqualTo(OrderEstado.CONFIRMADO);
        assertThat(ok.getComandadoAt()).isNull();
    }

    @Test
    @DisplayName("markComandado(): lote valido marca todos")
    void markComandadoBatch() {
        Order a = persistOrder(OrderEstado.CONFIRMADO);
        Order b = persistOrder(OrderEstado.CONFIRMADO);

        assertThat(service.markComandado(List.of(a.getId(), b.getId(), a.getId())))
            .hasSize(2)
            .allMatch(o -> o.getEstado() == OrderEstado.COMANDADO);
    }

    @Test
    @DisplayName("markComandado(): lista vacia da 400")
    void markComandadoEmptyList() {
        assertThatThrownBy(() -> service.markComandado(List.of()))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("markEntregado(): COMANDADO pasa a ENTREGADO y registra deliveredAt")
    void markEntregadoFromComandado() {
        Order o = persistOrder(OrderEstado.COMANDADO);

        Order result = service.markEntregado(List.of(o.getId())).get(0);

        assertThat(result.getEstado()).isEqualTo(OrderEstado.ENTREGADO);
        assertThat(result.getDeliveredAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("markEntregado(): rechaza un pedido CONFIRMADO")
    void markEntregadoRejectsConfirmado() {
        Order o = persistOrder(OrderEstado.CONFIRMADO);

        assertThatThrownBy(() -> service.markEntregado(List.of(o.getId())))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("order-invalid-transition"));
    }

    @Test
    @DisplayName("undo(): COMANDADO ya no se puede deshacer (409)")
    void undoRejectsComandado() {
        Order o = persistOrder(OrderEstado.CONFIRMADO);
        service.markComandado(List.of(o.getId()));

        assertThatThrownBy(() -> service.undo(o.getId()))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo("order-invalid-transition");
                assertThat(e.getStatus().value()).isEqualTo(409);
            });
        assertThat(orderRepo.findById(o.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.COMANDADO);
    }

    @Test
    @DisplayName("undo(): ENTREGADO vuelve a COMANDADO y limpia deliveredAt, conserva comandadoAt")
    void undoEntregado() {
        Order o = persistOrder(OrderEstado.CONFIRMADO);
        service.markComandado(List.of(o.getId()));
        service.markEntregado(List.of(o.getId()));

        Order result = service.undo(o.getId());

        assertThat(result.getEstado()).isEqualTo(OrderEstado.COMANDADO);
        assertThat(result.getDeliveredAt()).isNull();
        assertThat(result.getComandadoAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("undo(): CONFIRMADO no se puede deshacer")
    void undoRejectsConfirmado() {
        Order o = persistOrder(OrderEstado.CONFIRMADO);

        assertThatThrownBy(() -> service.undo(o.getId()))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("order-invalid-transition"));
    }

    @Test
    @DisplayName("undo(): id inexistente da 404")
    void undoUnknownId() {
        assertThatThrownBy(() -> service.undo(-1L))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("order-not-found"));
    }
}
