package com.arias.payments;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
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
 * Unidad B7 (feature b2c-ordering-redesign) — {@code POST
 * /api/v2/orders/direct-checkout} y {@code GET
 * /api/v2/orders/{id}/direct-checkout}: pagar un pedido directo sin saldo
 * suficiente, sin el doble cobro del camino viejo (cerrado en {@link
 * CreditPurchaseServiceTest}). Fixed clock (mismo patrón que {@code
 * OrderPlacementServiceTest}) porque {@link
 * CreditPurchaseService#createDirectCheckout} valida el horario de retiro
 * real vía {@code PickupSlotService}.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(DirectCheckoutServiceTest.FixedClockConfig.class)
class DirectCheckoutServiceTest {

    // Misma ventana fija que OrderPlacementServiceTest: dentro del horario
    // de servicio por defecto (11:00-15:00 ART) una vez sumado el offset.
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

    @Autowired
    private CreditPurchaseService purchaseService;

    @Autowired
    private CreditPurchaseRepository purchaseRepo;

    @Autowired
    private CreditPackRepository packRepo;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private CategoryRepository categoryRepo;

    @Autowired
    private MenuSectionRepository menuSectionRepo;

    @Autowired
    private DishRepository dishRepo;

    @Autowired
    private CreditWalletRepository walletRepo;

    @Autowired
    private CreditMovementRepository movementRepo;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private User persistB2cUser() {
        User user = User.builder()
            .email("direct-checkout-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone("+549" + (1155660100L + PHONE_SEQ.incrementAndGet()))
            .nickname("Apodo-" + System.nanoTime())
            .build();
        return userRepo.save(user);
    }

    private Category persistCategory(int creditCost) {
        return categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(creditCost).build());
    }

    private MenuSection persistMenuSection() {
        return menuSectionRepo.save(MenuSection.builder()
            .nombre("Seccion-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
    }

    private Dish persistDish(Category category, MenuSection section, int stock) {
        return dishRepo.save(Dish.builder()
            .nombre("Plato-" + System.nanoTime())
            .category(category)
            .menuSection(section)
            .enabled(true)
            .especial(false)
            .stockDiarioDefault(stock)
            .stockActual(stock)
            .build());
    }

    private CreditPack persistDayPack(int creditAmount, long priceCents) {
        return packRepo.save(CreditPack.builder()
            .code("DAY")
            .nombre("Día")
            .creditAmount(creditAmount)
            .priceCents(priceCents)
            .discountPercent(0)
            .ordenDisplay(0)
            .enabled(true)
            .build());
    }

    private PlaceOrderV2Request singleItemRequest(Long dishId, Instant pickupAt) {
        return new PlaceOrderV2Request(
            List.of(new PlaceOrderV2Request.OrderItemRequest(dishId, null, null)),
            pickupAt,
            null
        );
    }

    private static Instant defaultPickupAt() {
        return FIXED_NOW.plus(60, ChronoUnit.MINUTES);
    }

    // ─── POST /api/v2/orders/direct-checkout ────────────────────────────────

    @Test
    @DisplayName("createDirectCheckout(): con 0 créditos crea un pedido PENDIENTE_PAGO, reserva stock, no genera movimiento y calcula el importe desde el pack DAY")
    void createDirectCheckoutConCeroCreditosReservaStockYCalculaImporteDesdeDayPack() {
        User user = persistB2cUser();
        // Créditos = 0 a propósito: el caso de uso es justamente el cliente
        // sin saldo suficiente — no se crea ninguna billetera.
        persistDayPack(2, 3_000L); // 1500 centavos por crédito (redondeo hacia arriba)
        Category category = persistCategory(4);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-direct-checkout", "https://mp.test/init-direct"));

        DirectCheckoutDto dto = purchaseService.createDirectCheckout(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt()));

        assertThat(dto.orderId()).isNotNull();
        assertThat(dto.purchaseId()).isNotNull();
        assertThat(dto.initPoint()).isEqualTo("https://mp.test/init-direct");

        entityManager.flush();
        entityManager.clear();

        Order order = orderRepo.findById(dto.orderId()).orElseThrow();
        assertThat(order.getEstado()).isEqualTo(OrderEstado.PENDIENTE_PAGO);
        assertThat(order.getCreditTotal()).isEqualTo(4);
        assertThat(order.getUser().getId()).isEqualTo(user.getId());

        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(4);

        // Ningún crédito comprometido — el pago cubre el pedido entero.
        assertThat(walletRepo.findById(user.getId())).isEmpty();
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();

        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getType()).isEqualTo(PurchaseType.DIRECT);
        assertThat(purchase.getStatus()).isEqualTo(CreditPurchaseStatus.PENDING);
        assertThat(purchase.getOrder().getId()).isEqualTo(order.getId());
        assertThat(purchase.getCreditAmount()).isEqualTo(4);
        // 1500 (ceil(3000/2)) * 4 créditos = 6000 — MISMA fórmula que la
        // compra directa vieja (directAmountCentsFor), nunca la manda el cliente.
        assertThat(purchase.getAmountCents()).isEqualTo(6_000L);
        assertThat(purchase.getInitPoint()).isEqualTo("https://mp.test/init-direct");
    }

    @Test
    @DisplayName("createDirectCheckout(): sin paquete DAY se rechaza con 503 y no deja pedido ni stock reservado")
    void createDirectCheckoutSinPaqueteDaySeRechazaYRevierteTodo() {
        User user = persistB2cUser();
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);

        assertThatThrownBy(() -> purchaseService.createDirectCheckout(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "direct-purchase-unavailable");

        assertThat(orderRepo.findAll()).isEmpty();
        assertThat(purchaseRepo.findAll()).isEmpty();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(5);
    }

    @Test
    @DisplayName("createDirectCheckout(): si Mercado Pago falla al crear el checkout, marca TODA la transacción para revertir — pedido, stock reservado y compra")
    void createDirectCheckoutFallaDeMercadoPagoRevierteTodo() {
        User user = persistB2cUser();
        persistDayPack(2, 3_000L);
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);

        when(paymentGateway.createCheckout(any()))
            .thenThrow(new BusinessException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "mp-unreachable", "Mercado Pago no responde"));

        // La orden y la compra SÍ llegan a insertarse dentro de esta MISMA
        // transacción antes de llamar a Mercado Pago (visibles acá porque el
        // test también es @Transactional y comparte la conexión/transacción
        // física con createDirectCheckout — no hay aislamiento de una
        // transacción respecto de sí misma). Lo que prueba que NADA de esto
        // sobrevive es que la excepción marcó la transacción rollback-only:
        // en producción (donde createDirectCheckout ES el límite transaccional
        // de la request) eso dispara un ROLLBACK real, exactamente como
        // ocurre hoy con createPurchase si el checkout de un PACK falla.
        assertThatThrownBy(() -> purchaseService.createDirectCheckout(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "mp-unreachable");

        assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
    }

    // ─── GET /api/v2/orders/{id}/direct-checkout (retomar un pago abandonado) ─

    @Test
    @DisplayName("resumeDirectCheckout(): el dueño retoma su pedido esperando pago y recibe el MISMO initPoint, sin crear una segunda compra")
    void resumeDirectCheckoutDevuelveElMismoInitPoint() {
        User user = persistB2cUser();
        persistDayPack(2, 3_000L);
        Category category = persistCategory(4);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-resume", "https://mp.test/init-resume"));
        DirectCheckoutDto created = purchaseService.createDirectCheckout(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt()));

        entityManager.flush();
        entityManager.clear();

        DirectCheckoutDto resumed = purchaseService.resumeDirectCheckout(user.getId(), created.orderId());

        assertThat(resumed.orderId()).isEqualTo(created.orderId());
        assertThat(resumed.purchaseId()).isEqualTo(created.purchaseId());
        assertThat(resumed.initPoint()).isEqualTo("https://mp.test/init-resume");

        // Nunca un segundo checkout/cobro para el mismo pedido.
        assertThat(purchaseRepo.findAll()).hasSize(1);
        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.times(1)).createCheckout(any());
    }

    @Test
    @DisplayName("resumeDirectCheckout(): un pedido que ya no está esperando pago se rechaza con 409 order-not-awaiting-payment")
    void resumeDirectCheckoutRechazaPedidoNoEsperandoPago() {
        User user = persistB2cUser();
        Order paid = orderRepo.save(Order.builder()
            .user(user)
            .fecha(java.time.LocalDate.ofInstant(defaultPickupAt(), ZONE))
            .pickupAt(defaultPickupAt())
            .estado(OrderEstado.PENDIENTE)
            .creditTotal(2)
            .build());

        assertThatThrownBy(() -> purchaseService.resumeDirectCheckout(user.getId(), paid.getId()))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-awaiting-payment");
    }

    @Test
    @DisplayName("resumeDirectCheckout(): un pedido de otro usuario se rechaza con order-not-found, igual que el resto de los endpoints v2")
    void resumeDirectCheckoutRechazaSiNoEsElDueño() {
        User owner = persistB2cUser();
        User other = persistB2cUser();
        Order order = orderRepo.save(Order.builder()
            .user(owner)
            .fecha(java.time.LocalDate.ofInstant(defaultPickupAt(), ZONE))
            .pickupAt(defaultPickupAt())
            .estado(OrderEstado.PENDIENTE_PAGO)
            .creditTotal(2)
            .build());

        assertThatThrownBy(() -> purchaseService.resumeDirectCheckout(other.getId(), order.getId()))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-found");
    }
}
