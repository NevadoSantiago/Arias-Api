package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.common.security.JwtUser;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.credits.CreditMovement;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.MovementType;
import com.arias.payments.CreditPurchase;
import com.arias.payments.CreditPurchaseRepository;
import com.arias.payments.CreditPurchaseStatus;
import com.arias.payments.PurchaseType;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unidad 7 — GREEN: {@link OrderPlacementService}, el camino nuevo por
 * créditos sobre {@link Order}/{@link OrderItem}. Cubre spec {@code
 * order-placement}: múltiples pedidos por día, múltiples ítems con total =
 * suma, bloqueo atómico por saldo insuficiente (sin compromiso parcial ni
 * stock decrementado), stock agotado rechaza el ítem ANTES de comprometer
 * créditos, y que un empleado de empresa también consuma créditos sin dejar
 * de tener {@code company} como instantánea.
 *
 * <p>{@link OrderService} (sobre {@code DailyChoice}) NO se toca por esta
 * unidad — ver la nota de transición en {@code tasks.md} unidad 7 y {@link
 * OrderServiceCompanyFlowTest}, que sigue verde sin modificaciones.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(OrderPlacementServiceTest.FixedClockConfig.class)
class OrderPlacementServiceTest {

    // 2026-03-10 10:40 ART (UTC-3) — dentro de la ventana de pedidos por
    // defecto (11:00-15:00 ART, V20) una vez sumados los offsets usados
    // abajo (+20/+60/+90 min); unidad 8 agrega la validación de ventana de
    // servicio que estos horarios deben respetar (antes solo se validaba el
    // lead de 20 minutos).
    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:40:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    // System.nanoTime() no alcanza para distinguir dos persistB2cUser()
    // seguidos dentro del mismo test (colisiona en uq_users_phone) — un
    // contador monotónico sí garantiza unicidad.
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
    private OrderPlacementService orderPlacementService;

    @Autowired
    private OrderPlacementController orderPlacementController;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private CompanyRepository companyRepo;

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
    private CreditPurchaseRepository purchaseRepo;

    @Autowired
    private OrderItemRepository orderItemRepo;

    @Autowired
    private Validator validator;

    /** Ver el comentario equivalente en {@code OrderServiceCompanyFlowTest}: los
     *  UPDATE en bloque de {@code DishRepository} no refrescan el first-level
     *  cache — hace falta limpiar el contexto de persistencia para releer. */
    @Autowired
    private EntityManager entityManager;

    private Category persistCategory(int creditCost) {
        Category category = Category.builder()
            .nombre("Categoria-" + System.nanoTime())
            .ordenDisplay(0)
            .enabled(true)
            .creditCost(creditCost)
            .build();
        return categoryRepo.save(category);
    }

    private MenuSection persistMenuSection() {
        MenuSection section = MenuSection.builder()
            .nombre("seccion-" + System.nanoTime())
            .ordenDisplay(0)
            .enabled(true)
            .build();
        return menuSectionRepo.save(section);
    }

    private Dish persistDish(Category category, MenuSection section, int stock) {
        Dish dish = Dish.builder()
            .nombre("Plato-" + System.nanoTime())
            .category(category)
            .menuSection(section)
            .enabled(true)
            .especial(false)
            .stockDiarioDefault(stock)
            .stockActual(stock)
            .build();
        return dishRepo.save(dish);
    }

    /**
     * B2C con el correo ya verificado y perfil completo (teléfono + apodo) —
     * el caso feliz que usa el resto de la suite. Refleja el autorregistro
     * real: {@code RegisterRequest} exige ambos campos, así que un B2C
     * verificado siempre los tiene salvo que haya entrado con Google y
     * todavía no pasó por {@code complete-profile} (unidad 9, gap de perfil
     * incompleto — ver {@link #persistB2cUserConPerfilIncompleto}).
     */
    private User persistB2cUser() {
        User user = User.builder()
            .email("b2c-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone("+549" + (1122330100L + PHONE_SEQ.incrementAndGet()))
            .nickname("Apodo-" + System.nanoTime())
            .build();
        return userRepo.save(user);
    }

    /**
     * B2C con correo verificado (típico de login con Google, diseño
     * §Decisión 9) pero sin teléfono y/o apodo — todavía no pasó por
     * {@code complete-profile}. Gap de la unidad 9.
     */
    private User persistB2cUserConPerfilIncompleto(String phone, String nickname) {
        User user = User.builder()
            .email("b2c-sin-perfil-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone(phone)
            .nickname(nickname)
            .build();
        return userRepo.save(user);
    }

    /** B2C autorregistrado que todavía no verificó el correo — gap de la unidad 4/5. */
    private User persistUnverifiedB2cUser() {
        User user = User.builder()
            .email("b2c-sin-verificar-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .build();
        return userRepo.save(user);
    }

    private User persistEmployee(Company company, Category category) {
        User user = User.builder()
            .email("empleado-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .company(company)
            .category(category)
            .active(true)
            .build();
        return userRepo.save(user);
    }

    private Company persistCompany(Category categoriaDefault) {
        Company company = Company.builder()
            .nombre("Empresa-" + System.nanoTime())
            .cuit(String.valueOf(20000000000L + (System.nanoTime() % 9000000000L)))
            .calle("Calle Falsa")
            .altura("123")
            .horaEntrega(java.time.LocalTime.of(13, 0))
            .categoriaDefault(categoriaDefault)
            .enabled(true)
            .build();
        return companyRepo.save(company);
    }

    private void seedWallet(Long userId, int available) {
        walletRepo.saveAndFlush(CreditWallet.builder()
            .userId(userId)
            .available(available)
            .committed(0)
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

    // ─── Múltiples pedidos por día ─────────────────────────────────────────

    @Test
    @DisplayName("place(): un usuario puede hacer un segundo pedido el mismo día")
    void placeAceptaMultiplesPedidosElMismoDia() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto first = orderPlacementService.place(user.getId(), singleItemRequest(dish.getId(), defaultPickupAt()));
        OrderDto second = orderPlacementService.place(user.getId(), singleItemRequest(dish.getId(), defaultPickupAt()));

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(orderRepo.findByUserIdAndFecha(user.getId(), first.fecha())).hasSize(2);
    }

    // ─── Total = suma de los ítems ──────────────────────────────────────────

    @Test
    @DisplayName("place(): el total del pedido es la suma del creditCost de cada ítem")
    void placeCalculaTotalComoSumaDeLosItems() {
        Category categoriaUno = persistCategory(1);
        Category categoriaDos = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(categoriaUno, section, 5);
        Dish dishB = persistDish(categoriaUno, section, 5);
        Dish dishC = persistDish(categoriaDos, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        PlaceOrderV2Request req = new PlaceOrderV2Request(
            List.of(
                new PlaceOrderV2Request.OrderItemRequest(dishA.getId(), null, null),
                new PlaceOrderV2Request.OrderItemRequest(dishB.getId(), null, null),
                new PlaceOrderV2Request.OrderItemRequest(dishC.getId(), null, null)
            ),
            defaultPickupAt(),
            null
        );

        OrderDto order = orderPlacementService.place(user.getId(), req);

        assertThat(order.creditTotal()).isEqualTo(4); // 1 + 1 + 2
        assertThat(order.items()).hasSize(3);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(6);
        assertThat(wallet.getCommitted()).isEqualTo(4);
    }

    // ─── Stock agotado rechaza el ítem ANTES de comprometer créditos ───────

    @Test
    @DisplayName("place(): stock agotado rechaza el pedido y no toca el saldo de créditos")
    void placeRechazaPorStockAgotadoSinTocarCreditos() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 0); // sin stock
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        assertThatThrownBy(() -> orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "out-of-stock");

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(10);
        assertThat(wallet.getCommitted()).isZero();
    }

    // ─── Saldo insuficiente bloquea TODO el pedido, sin compromiso parcial ──

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("place(): saldo insuficiente bloquea el pedido completo — sin commit parcial y sin stock decrementado")
    void placeSaldoInsuficienteBloqueaTodoSinCommitParcialNiStockDecrementado() {
        Category category = persistCategory(3);
        MenuSection section = persistMenuSection();
        Dish dishUno = persistDish(category, section, 5);
        Dish dishDos = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 1); // alcanza para 1 pero el pedido cuesta 6 (3+3)

        PlaceOrderV2Request req = new PlaceOrderV2Request(
            List.of(
                new PlaceOrderV2Request.OrderItemRequest(dishUno.getId(), null, null),
                new PlaceOrderV2Request.OrderItemRequest(dishDos.getId(), null, null)
            ),
            defaultPickupAt(),
            null
        );

        try {
            assertThatThrownBy(() -> orderPlacementService.place(user.getId(), req))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "insufficient-credits");

            entityManager.clear();
            assertThat(dishRepo.findById(dishUno.getId()).orElseThrow().getStockActual()).isEqualTo(5);
            assertThat(dishRepo.findById(dishDos.getId()).orElseThrow().getStockActual()).isEqualTo(5);

            // findByIdForUpdate() usa @Lock(PESSIMISTIC_WRITE): exige una
            // transacción activa, que este test suspendió a propósito. Para
            // solo LEER acá alcanza con findById (sin bloqueo).
            CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
            assertThat(wallet.getAvailable()).isEqualTo(1);
            assertThat(wallet.getCommitted()).isZero();

            assertThat(orderRepo.findByUserIdAndFecha(user.getId(),
                java.time.LocalDate.ofInstant(defaultPickupAt(), ZONE))).isEmpty();
        } finally {
            // Este test suspende la transacción de rollback ambiental (mismo
            // motivo que el test equivalente en CreditLedgerServiceTest: hace
            // falta que place() abra y cierre su propia transacción física
            // para poder observar el rollback atómico real). Limpieza manual.
            orderRepo.findByUserIdAndFecha(user.getId(),
                java.time.LocalDate.ofInstant(defaultPickupAt(), ZONE)).forEach(orderRepo::delete);
            walletRepo.findById(user.getId()).ifPresent(walletRepo::delete);
            userRepo.delete(user);
            dishRepo.delete(dishUno);
            dishRepo.delete(dishDos);
            categoryRepo.delete(category);
            menuSectionRepo.delete(section);
        }
    }

    // ─── cancel(): libera créditos, restaura stock, soft-cancel ────────────

    @Test
    @DisplayName("cancel(): dentro de la ventana, libera créditos, restaura stock y soft-cancela (no borra)")
    void cancelDentroDeVentanaLiberaCreditosYRestauraStock() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        // pickupAt bien lejos del deadline de cancelación (lead = 20 min).
        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));

        // flush() antes de clear(): el UPDATE de credit_wallet dentro de
        // CreditLedgerService.apply() queda pendiente por dirty-checking
        // (CreditWallet no usa IDENTITY, no se fuerza el INSERT/UPDATE
        // inmediato como con Order). clear() sin flush() previo lo
        // descartaría del todo — a diferencia de decrementStock/incrementStock,
        // que son UPDATE en bloque y ya pegaron en la base al ejecutarse.
        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(2);

        orderPlacementService.cancel(user.getId(), placed.id());

        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(3);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(10);
        assertThat(wallet.getCommitted()).isZero();

        Order cancelled = orderRepo.findById(placed.id()).orElseThrow();
        assertThat(cancelled.getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(cancelled.getCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("cancel(): rechaza si ya no falta más de `lead` minutos para el retiro")
    void cancelRechazaFueraDeVentana() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        // pickupAt = ahora + 20 (el mínimo aceptado por place()); el deadline de
        // cancelación (pickupAt - 20) cae exactamente en "ahora" → ya cerrado.
        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(20, ChronoUnit.MINUTES)));

        assertThatThrownBy(() -> orderPlacementService.cancel(user.getId(), placed.id()))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "cancel-window-closed");

        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(2);
        Order stillPending = orderRepo.findById(placed.id()).orElseThrow();
        assertThat(stillPending.getEstado()).isEqualTo(OrderEstado.PENDIENTE);
    }

    // ─── placeAwaitingPayment() / B7: pedido "esperando pago" ──────────────

    @Test
    @DisplayName("placeAwaitingPayment(): crea el pedido PENDIENTE_PAGO, reserva stock, y NO compromete créditos")
    void placeAwaitingPaymentReservaStockSinComprometerCreditos() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User user = persistB2cUser();
        // Saldo CERO a propósito: el pago directo es justamente para un
        // cliente sin almuerzos suficientes — placeAwaitingPayment() nunca
        // debe tocar el libro mayor, así que ni siquiera necesita billetera.

        Order order = orderPlacementService.placeAwaitingPayment(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt()));

        assertThat(order.getEstado()).isEqualTo(OrderEstado.PENDIENTE_PAGO);
        assertThat(order.getCreditTotal()).isEqualTo(2);

        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(2);

        assertThat(walletRepo.findById(user.getId())).isEmpty();
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("placeAwaitingPayment(): rechaza un horario de retiro inválido exactamente como place()")
    void placeAwaitingPaymentValidaHorarioIgualQuePlace() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User user = persistB2cUser();

        assertThatThrownBy(() -> orderPlacementService.placeAwaitingPayment(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(1, ChronoUnit.MINUTES))))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "pickup-outside-service-window");

        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(3);
    }

    // ─── cancel() de un pedido esperando pago (B7): sin liberar créditos ───

    @Test
    @DisplayName("cancel(): un pedido PENDIENTE_PAGO se puede cancelar, restaura stock y NO libera créditos (nunca se comprometieron)")
    void cancelPedidoEsperandoPagoRestauraStockSinLiberarCreditos() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User user = persistB2cUser();

        Order order = orderPlacementService.placeAwaitingPayment(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));

        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(2);

        orderPlacementService.cancel(user.getId(), order.getId());

        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(3);

        // Sin billetera (o vacía): nunca se comprometió nada, así que no hay
        // nada que liberar — a diferencia de cancelar un pedido PENDIENTE.
        assertThat(walletRepo.findById(user.getId())
            .map(w -> w.getAvailable() + w.getCommitted()).orElse(0)).isZero();
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();

        Order cancelled = orderRepo.findById(order.getId()).orElseThrow();
        assertThat(cancelled.getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(cancelled.getCancelledAt()).isNotNull();
    }

    // ─── B7: un pedido PENDIENTE_PAGO nunca es MODIFICABLE ─────────────────

    @Test
    @DisplayName("addItems(): rechaza un pedido PENDIENTE_PAGO con 409 order-not-modifiable")
    void addItemsRechazaPedidoEsperandoPago() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User user = persistB2cUser();

        Order order = orderPlacementService.placeAwaitingPayment(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt()));

        assertThatThrownBy(() -> orderPlacementService.addItems(user.getId(), order.getId(),
            new AddOrderItemsRequest(List.of(new PlaceOrderV2Request.OrderItemRequest(dish.getId(), null, null)))))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-modifiable");
    }

    @Test
    @DisplayName("removeItem(): rechaza un pedido PENDIENTE_PAGO con 409 order-not-modifiable")
    void removeItemRechazaPedidoEsperandoPago() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User user = persistB2cUser();

        Order order = orderPlacementService.placeAwaitingPayment(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt()));
        Long itemId = order.getItems().get(0).getId();

        assertThatThrownBy(() -> orderPlacementService.removeItem(user.getId(), order.getId(), itemId))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-modifiable");
    }

    // ─── Empleado de empresa también consume créditos ──────────────────────

    @Test
    @DisplayName("place(): un empleado de empresa consume créditos igual que un B2C, con company como instantánea")
    void placeEmpleadoDeEmpresaConsumeCreditosYConservaCompanyComoInstantanea() {
        Category category = persistCategory(3);
        MenuSection section = persistMenuSection();
        Company company = persistCompany(category);
        Dish dish = persistDish(category, section, 5);
        User employee = persistEmployee(company, category);
        seedWallet(employee.getId(), 10);

        OrderDto order = orderPlacementService.place(employee.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt()));

        assertThat(order.creditTotal()).isEqualTo(3);
        Order saved = orderRepo.findById(order.id()).orElseThrow();
        assertThat(saved.getCompany().getId()).isEqualTo(company.getId());

        CreditWallet wallet = walletRepo.findByIdForUpdate(employee.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(7);
        assertThat(wallet.getCommitted()).isEqualTo(3);
    }

    // ─── Gate de verificación de correo (gap de la unidad 4/5, no de login) ─

    @Test
    @DisplayName("place(): un B2C sin verificar el correo no puede pedir — 409 email-not-verified, sin tocar stock ni saldo")
    void placeRechazaB2cSinVerificarElCorreo() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistUnverifiedB2cUser();
        seedWallet(user.getId(), 10);

        assertThatThrownBy(() -> orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "email-not-verified");

        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(5);
        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(10);
        assertThat(wallet.getCommitted()).isZero();
        assertThat(orderRepo.findByUserIdAndFecha(user.getId(),
            java.time.LocalDate.ofInstant(defaultPickupAt(), ZONE))).isEmpty();
    }

    @Test
    @DisplayName("place(): un B2C con el correo verificado puede pedir")
    void placeAceptaB2cConCorreoVerificado() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto order = orderPlacementService.place(user.getId(), singleItemRequest(dish.getId(), defaultPickupAt()));

        assertThat(order.id()).isNotNull();
    }

    @Test
    @DisplayName("place(): un empleado de empresa con email_verified_at = NULL Y sin teléfono/apodo SÍ puede pedir — exento de ambos gates (regresión V16)")
    void placeNoBloqueaEmpleadoDeEmpresaConEmailVerifiedAtNulo() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Company company = persistCompany(category);
        Dish dish = persistDish(category, section, 5);
        User employee = persistEmployee(company, category);
        assertThat(employee.getEmailVerifiedAt()).isNull(); // exactamente el caso que V16 no pudo rellenar
        assertThat(employee.getPhone()).isNull(); // el alta por lista blanca nunca captura teléfono ni apodo
        assertThat(employee.getNickname()).isNull();
        seedWallet(employee.getId(), 10);

        OrderDto order = orderPlacementService.place(employee.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt()));

        assertThat(order.id()).isNotNull();
    }

    // ─── Gate de perfil incompleto (gap de la unidad 9, diseño §Decisión 9) ─

    @Test
    @DisplayName("place(): un B2C con correo verificado pero SIN teléfono no puede pedir — 409 profile-incomplete, sin tocar stock ni saldo")
    void placeRechazaB2cSinTelefono() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUserConPerfilIncompleto(null, "Apodo-" + System.nanoTime());
        seedWallet(user.getId(), 10);

        assertThatThrownBy(() -> orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "profile-incomplete");

        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(5);
        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(10);
        assertThat(wallet.getCommitted()).isZero();
    }

    @Test
    @DisplayName("place(): un B2C con correo verificado pero SIN apodo no puede pedir — 409 profile-incomplete")
    void placeRechazaB2cSinApodo() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUserConPerfilIncompleto("+549" + (1122330200L + PHONE_SEQ.incrementAndGet()), null);
        seedWallet(user.getId(), 10);

        assertThatThrownBy(() -> orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "profile-incomplete");
    }

    @Test
    @DisplayName("place(): un B2C con correo verificado y perfil completo puede pedir")
    void placeAceptaB2cConPerfilCompleto() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        assertThat(user.getPhone()).isNotBlank();
        assertThat(user.getNickname()).isNotBlank();
        seedWallet(user.getId(), 10);

        OrderDto order = orderPlacementService.place(user.getId(), singleItemRequest(dish.getId(), defaultPickupAt()));

        assertThat(order.id()).isNotNull();
    }

    @Test
    @DisplayName("place(): con AMBOS gates disparables, el B2C ve email-not-verified primero (identidad antes que datos de perfil)")
    void placeConAmbosGatesDisparablesMuestraEmailNotVerifiedPrimero() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        // Sin verificar Y sin teléfono/apodo — ambos gates aplicarían.
        User user = persistUnverifiedB2cUser();
        assertThat(user.getPhone()).isNull();
        assertThat(user.getNickname()).isNull();
        seedWallet(user.getId(), 10);

        assertThatThrownBy(() -> orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "email-not-verified");
    }

    // ─── list(): "mis pedidos" (gap fix — no numbered tasks.md unit) ───────
    //
    // Identifiers/DisplayNames in English per this gap fix's explicit
    // instruction, unlike the Spanish identifiers above from the original
    // unit-7 batch.

    @Test
    @DisplayName("list(): a customer sees their own orders with items and credit totals")
    void listReturnsOwnOrdersWithItemsAndCreditTotal() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), defaultPickupAt()));

        List<OrderDto> orders = orderPlacementService.list(user.getId());

        assertThat(orders).hasSize(1);
        OrderDto found = orders.get(0);
        assertThat(found.id()).isEqualTo(placed.id());
        assertThat(found.creditTotal()).isEqualTo(2);
        assertThat(found.items()).hasSize(1);
        assertThat(found.items().get(0).dishNombre()).isEqualTo(dish.getNombre());
        assertThat(found.items().get(0).creditCost()).isEqualTo(2);
    }

    @Test
    @DisplayName("list(): a customer does NOT see another customer's orders")
    void listDoesNotReturnAnotherCustomersOrders() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User owner = persistB2cUser();
        User stranger = persistB2cUser();
        seedWallet(owner.getId(), 10);
        seedWallet(stranger.getId(), 10);

        orderPlacementService.place(owner.getId(), singleItemRequest(dish.getId(), defaultPickupAt()));

        List<OrderDto> strangerOrders = orderPlacementService.list(stranger.getId());

        assertThat(strangerOrders).isEmpty();
    }

    @Test
    @DisplayName("list(): cancellable is true before the deadline and false after it")
    void listMarksCancellableBeforeDeadlineAndNotAfter() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dishFarAway = persistDish(category, section, 5);
        Dish dishAtDeadline = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        // Lead is 20 minutes (V20 default). Well before the deadline → cancellable.
        OrderDto farAway = orderPlacementService.place(user.getId(),
            singleItemRequest(dishFarAway.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        // Earliest pickup allowed by PickupSlotService (now + lead) → the
        // cancellation deadline (pickupAt - lead) equals "now" exactly, so
        // `now < deadline` is false → NOT cancellable (same boundary as
        // cancelRechazaFueraDeVentana above, now exposed via list()).
        OrderDto atDeadline = orderPlacementService.place(user.getId(),
            singleItemRequest(dishAtDeadline.getId(), FIXED_NOW.plus(20, ChronoUnit.MINUTES)));

        List<OrderDto> orders = orderPlacementService.list(user.getId());

        OrderDto foundFarAway = orders.stream().filter(o -> o.id().equals(farAway.id())).findFirst().orElseThrow();
        OrderDto foundAtDeadline = orders.stream().filter(o -> o.id().equals(atDeadline.id())).findFirst().orElseThrow();

        assertThat(foundFarAway.cancellable()).isTrue();
        assertThat(foundAtDeadline.cancellable()).isFalse();
    }

    @Test
    @DisplayName("list(): a cancelled order still appears, marked CANCELADO and not cancellable")
    void listShowsCancelledOrderWithCancelledState() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        orderPlacementService.cancel(user.getId(), placed.id());

        List<OrderDto> orders = orderPlacementService.list(user.getId());

        OrderDto found = orders.stream().filter(o -> o.id().equals(placed.id())).findFirst().orElseThrow();
        assertThat(found.estado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(found.cancellable()).isFalse();
    }

    // ─── B6: modify an existing order (add/remove items) ──────────────────

    private AddOrderItemsRequest addItemsRequest(Long dishId) {
        return new AddOrderItemsRequest(
            List.of(new PlaceOrderV2Request.OrderItemRequest(dishId, null, null)));
    }

    @Test
    @DisplayName("addItems(): grows creditTotal, commits credits for the added items, decrements stock and records a movement")
    void addItemsGrowsTotalCommitsCreditsAndDecrementsStock() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dishA.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        assertThat(placed.creditTotal()).isEqualTo(2);

        OrderDto updated = orderPlacementService.addItems(user.getId(), placed.id(), addItemsRequest(dishB.getId()));

        assertThat(updated.creditTotal()).isEqualTo(4);
        assertThat(updated.items()).hasSize(2);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(6);
        assertThat(wallet.getCommitted()).isEqualTo(4);

        entityManager.clear();
        assertThat(dishRepo.findById(dishB.getId()).orElseThrow().getStockActual()).isEqualTo(4);

        List<CreditMovement> movements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        CreditMovement last = movements.get(0);
        assertThat(last.getType()).isEqualTo(MovementType.COMMIT);
        assertThat(last.getOrderId()).isEqualTo(placed.id());
        assertThat(last.getDeltaCommitted()).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("addItems(): insufficient balance rejects with the same error as placing — nothing changes")
    void addItemsRechazaPorSaldoInsuficienteSinTocarNada() {
        Category category = persistCategory(3);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 3); // exactamente el costo del primer ítem

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dishA.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));

        try {
            assertThatThrownBy(() -> orderPlacementService.addItems(user.getId(), placed.id(), addItemsRequest(dishB.getId())))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "insufficient-credits");

            entityManager.clear();
            assertThat(dishRepo.findById(dishB.getId()).orElseThrow().getStockActual()).isEqualTo(5);
            assertThat(orderRepo.findById(placed.id()).orElseThrow().getCreditTotal()).isEqualTo(3);

            CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
            assertThat(wallet.getAvailable()).isZero();
            assertThat(wallet.getCommitted()).isEqualTo(3);
        } finally {
            // A diferencia del test equivalente de place(), acá el COMMIT de
            // place() SÍ llegó a persistirse (esta prueba solo falla en el
            // segundo llamado, addItems()) — credit_movement.user_id tiene FK
            // a users, así que hay que borrar el movimiento antes que el
            // usuario o la limpieza misma revienta.
            orderRepo.findById(placed.id()).ifPresent(orderRepo::delete);
            movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()).forEach(movementRepo::delete);
            walletRepo.findById(user.getId()).ifPresent(walletRepo::delete);
            userRepo.delete(user);
            dishRepo.delete(dishA);
            dishRepo.delete(dishB);
            categoryRepo.delete(category);
            menuSectionRepo.delete(section);
        }
    }

    @Test
    @DisplayName("addItems(): an unavailable dish rejects with the same error as placing")
    void addItemsRechazaPlatoDeshabilitado() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        dishB.setEnabled(false);
        dishRepo.save(dishB);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dishA.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));

        assertThatThrownBy(() -> orderPlacementService.addItems(user.getId(), placed.id(), addItemsRequest(dishB.getId())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "dish-disabled");
    }

    @Test
    @DisplayName("addItems(): rejects on a non-modifiable order — past the cancellation deadline")
    void addItemsRechazaPedidoNoModificablePorVentanaCerrada() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        // Deadline exactamente en "ahora" — mismo límite que cancelRechazaFueraDeVentana.
        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dishA.getId(), FIXED_NOW.plus(20, ChronoUnit.MINUTES)));

        assertThatThrownBy(() -> orderPlacementService.addItems(user.getId(), placed.id(), addItemsRequest(dishB.getId())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-modifiable");
    }

    @Test
    @DisplayName("addItems(): rejects on a non-modifiable order — already CONFIRMADO")
    void addItemsRechazaPedidoConfirmado() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dishA.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        Order order = orderRepo.findById(placed.id()).orElseThrow();
        order.setEstado(OrderEstado.CONFIRMADO);
        orderRepo.save(order);

        assertThatThrownBy(() -> orderPlacementService.addItems(user.getId(), placed.id(), addItemsRequest(dishB.getId())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-modifiable");
    }

    @Test
    @DisplayName("addItems(): a non-owner is rejected exactly like cancelling someone else's order")
    void addItemsRechazaSiNoEsElDueño() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User owner = persistB2cUser();
        User stranger = persistB2cUser();
        seedWallet(owner.getId(), 10);
        seedWallet(stranger.getId(), 10);

        OrderDto placed = orderPlacementService.place(owner.getId(),
            singleItemRequest(dishA.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));

        assertThatThrownBy(() -> orderPlacementService.addItems(stranger.getId(), placed.id(), addItemsRequest(dishB.getId())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-found");
    }

    @Test
    @DisplayName("removeItem(): releases credits, restores stock and lowers creditTotal — not the last item")
    void removeItemLiberaCreditosYRestauraStockSinCancelar() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        PlaceOrderV2Request req = new PlaceOrderV2Request(
            List.of(
                new PlaceOrderV2Request.OrderItemRequest(dishA.getId(), null, null),
                new PlaceOrderV2Request.OrderItemRequest(dishB.getId(), null, null)
            ),
            FIXED_NOW.plus(90, ChronoUnit.MINUTES),
            null
        );
        OrderDto placed = orderPlacementService.place(user.getId(), req);
        assertThat(placed.creditTotal()).isEqualTo(4);
        Long itemBId = placed.items().stream()
            .filter(i -> i.dishId().equals(dishB.getId())).findFirst().orElseThrow().id();

        OrderDto updated = orderPlacementService.removeItem(user.getId(), placed.id(), itemBId);

        assertThat(updated.creditTotal()).isEqualTo(2);
        assertThat(updated.items()).hasSize(1);
        assertThat(updated.estado()).isEqualTo(OrderEstado.PENDIENTE);

        // flush() antes de clear(): el UPDATE de credit_wallet dentro de
        // CreditLedgerService.apply() queda pendiente por dirty-checking
        // (mismo motivo documentado en cancelDentroDeVentanaLiberaCreditosYRestauraStock).
        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dishB.getId()).orElseThrow().getStockActual()).isEqualTo(5);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(8);
        assertThat(wallet.getCommitted()).isEqualTo(2);

        // Gap de cobertura (revisión B6.1): releer el pedido de la base — no
        // solo confiar en el DTO devuelto — prueba que el ítem quitado y el
        // creditTotal nuevo quedaron REALMENTE persistidos, no solo en la
        // instancia en memoria de removeItem().
        Order reloaded = orderRepo.findById(placed.id()).orElseThrow();
        assertThat(reloaded.getCreditTotal()).isEqualTo(2);
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getItems().get(0).getDish().getId()).isEqualTo(dishA.getId());

        CreditMovement release = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(m -> m.getType() == MovementType.RELEASE)
            .findFirst()
            .orElseThrow();
        assertThat(release.getOrderId()).isEqualTo(placed.id());
        assertThat(release.getDeltaCommitted()).isEqualTo(-2); // costo del ítem quitado (dishB)
        assertThat(release.getDeltaAvailable()).isEqualTo(2);
    }

    @Test
    @DisplayName("removeItem(): removing the last item cancels the whole order — released exactly once")
    void removeItemUltimoItemCancelaElPedido() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        Long itemId = placed.items().get(0).id();

        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(2);

        OrderDto updated = orderPlacementService.removeItem(user.getId(), placed.id(), itemId);

        assertThat(updated.estado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(updated.cancellable()).isFalse();

        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(3);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(10);
        assertThat(wallet.getCommitted()).isZero();

        List<CreditMovement> releases = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(m -> m.getType() == MovementType.RELEASE)
            .toList();
        assertThat(releases).hasSize(1);
        assertThat(releases.get(0).getDeltaCommitted()).isEqualTo(-2);

        Order cancelled = orderRepo.findById(placed.id()).orElseThrow();
        assertThat(cancelled.getCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("removeItem(): rejects on a non-modifiable order")
    void removeItemRechazaPedidoNoModificable() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(20, ChronoUnit.MINUTES)));
        Long itemId = placed.items().get(0).id();

        assertThatThrownBy(() -> orderPlacementService.removeItem(user.getId(), placed.id(), itemId))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-modifiable");
    }

    @Test
    @DisplayName("removeItem(): a non-owner is rejected exactly like cancelling someone else's order")
    void removeItemRechazaSiNoEsElDueño() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 3);
        User owner = persistB2cUser();
        User stranger = persistB2cUser();
        seedWallet(owner.getId(), 10);
        seedWallet(stranger.getId(), 10);

        OrderDto placed = orderPlacementService.place(owner.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        Long itemId = placed.items().get(0).id();

        assertThatThrownBy(() -> orderPlacementService.removeItem(stranger.getId(), placed.id(), itemId))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-found");
    }

    @Test
    @DisplayName("removeItem(): an itemId from another order is rejected with 404")
    void removeItemRechazaItemDeOtroPedido() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto orderOne = orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        OrderDto orderTwo = orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        Long itemFromOrderTwo = orderTwo.items().get(0).id();

        assertThatThrownBy(() -> orderPlacementService.removeItem(user.getId(), orderOne.id(), itemFromOrderTwo))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-item-not-found");
    }

    // ─── B6.1 (revisión): concurrencia real en removeItem() ────────────────
    //
    // Sin lock, addItems/removeItem/cancel cargaban el pedido con un SELECT
    // plano (findByIdAndUserId). Dos removeItem() concurrentes sobre un
    // pedido de DOS ítems pueden leer cada uno items.size()==2 (la foto de
    // ANTES de que el otro hilo confirme su propio removeItem), así que
    // ninguno toma la rama "es el último ítem" — el pedido queda con CERO
    // ítems pero sigue PENDIENTE, y los créditos se liberan sin cancelar.
    //
    // Este test dispara los dos removeItem() en hilos separados con
    // transacciones reales (NOT_SUPPORTED suspende la transacción de test
    // para que cada llamada abra su propia transacción física, visible entre
    // conexiones — mismo patrón que los tests de rollback atómico de arriba).
    // Con el lock (findByIdAndUserIdForUpdate, PESSIMISTIC_WRITE) el segundo
    // hilo en llegar se BLOQUEA en el SELECT ... FOR UPDATE hasta que el
    // primero confirma, y entonces relee el estado YA actualizado (un solo
    // ítem) — así que el resultado final es determinístico pase lo que pase
    // el orden real de ejecución: el pedido SIEMPRE termina CANCELADO con
    // los créditos totalmente liberados, nunca PENDIENTE sin ítems.
    //
    // Nota de honestidad: la carrera en sí (que dos hilos lleguen a leer el
    // mismo estado stale) depende del scheduler del SO/JDBC y no está forzada
    // con ningún latch dentro del método bajo prueba — así que el RED (antes
    // del fix) no está 100% garantizado en cada corrida, aunque se observó al
    // ejecutar este test contra el código sin lock. El GREEN (después del
    // fix) sí es determinístico: el lock hace que el resultado final no
    // dependa de qué hilo gana la carrera.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("removeItem(): dos llamadas concurrentes sobre un pedido de 2 ítems serializan bajo el lock — nunca queda PENDIENTE sin ítems")
    void removeItemConcurrenteSerializaBajoElLockYNuncaDejaElPedidoVacioPendiente() throws Exception {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        PlaceOrderV2Request req = new PlaceOrderV2Request(
            List.of(
                new PlaceOrderV2Request.OrderItemRequest(dishA.getId(), null, null),
                new PlaceOrderV2Request.OrderItemRequest(dishB.getId(), null, null)
            ),
            FIXED_NOW.plus(90, ChronoUnit.MINUTES),
            null
        );
        OrderDto placed = orderPlacementService.place(user.getId(), req);
        Long itemAId = placed.items().stream()
            .filter(i -> i.dishId().equals(dishA.getId())).findFirst().orElseThrow().id();
        Long itemBId = placed.items().stream()
            .filter(i -> i.dishId().equals(dishB.getId())).findFirst().orElseThrow().id();

        try {
            CountDownLatch startLatch = new CountDownLatch(2);
            AtomicReference<Exception> errorA = new AtomicReference<>();
            AtomicReference<Exception> errorB = new AtomicReference<>();

            Thread threadA = new Thread(() -> {
                startLatch.countDown();
                awaitQuietly(startLatch);
                try {
                    orderPlacementService.removeItem(user.getId(), placed.id(), itemAId);
                } catch (Exception e) {
                    errorA.set(e);
                }
            });
            Thread threadB = new Thread(() -> {
                startLatch.countDown();
                awaitQuietly(startLatch);
                try {
                    orderPlacementService.removeItem(user.getId(), placed.id(), itemBId);
                } catch (Exception e) {
                    errorB.set(e);
                }
            });

            threadA.start();
            threadB.start();
            threadA.join(10_000);
            threadB.join(10_000);

            // Ambas llamadas deben terminar sin error: la segunda relee el
            // pedido ya actualizado por la primera (bajo el lock) en vez de
            // chocar contra él.
            assertThat(errorA.get()).isNull();
            assertThat(errorB.get()).isNull();

            // findById() fuera de transacción (NOT_SUPPORTED de este test): no
            // hay sesión para inicializar la colección LAZY items, así que se
            // cuenta por OrderItemRepository en vez de finalOrder.getItems().
            //
            // El ítem que dispara la rama "es el último" queda en la fila —
            // applyCancellation() (mismo camino que cancel()) es un soft-cancel
            // que NUNCA borra ítems, solo marca CANCELADO — así que queda
            // exactamente 1 fila (el ítem que el segundo hilo en llegar vio
            // como "el último"), no 0.
            Order finalOrder = orderRepo.findById(placed.id()).orElseThrow();
            assertThat(finalOrder.getEstado()).isEqualTo(OrderEstado.CANCELADO);
            assertThat(orderItemRepo.findByOrderId(placed.id())).hasSize(1);

            CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
            assertThat(wallet.getAvailable()).isEqualTo(10);
            assertThat(wallet.getCommitted()).isZero();
        } finally {
            orderRepo.findById(placed.id()).ifPresent(orderRepo::delete);
            movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()).forEach(movementRepo::delete);
            walletRepo.findById(user.getId()).ifPresent(walletRepo::delete);
            userRepo.delete(user);
            dishRepo.delete(dishA);
            dishRepo.delete(dishB);
            categoryRepo.delete(category);
            menuSectionRepo.delete(section);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── B6.1 (revisión): pedidos pagados por compra DIRECTA ───────────────
    //
    // Una compra DIRECT (Mercado Pago) acredita DIRECT_PURCHASE derecho a
    // COMMITTED, sin pasar por AVAILABLE (CreditPurchaseService#creditPurchase).
    // Si addItems/removeItem tocaran ese pedido moviendo AVAILABLE↔COMMITTED
    // de la billetera igual que un pedido pagado con saldo propio, el total
    // que la compra DIRECT ya cobra (o va a cobrar) por Mercado Pago quedaría
    // desincronizado del creditTotal real del pedido. Se rechaza con el mismo
    // código "order-not-modifiable" que ya usa assertModifiable, pero con un
    // mensaje que aclara el motivo real.

    private CreditPurchase persistDirectPurchase(User user, Order order, CreditPurchaseStatus status) {
        return purchaseRepo.save(CreditPurchase.builder()
            .user(user)
            .type(PurchaseType.DIRECT)
            .order(order)
            .creditAmount(order.getCreditTotal())
            .amountCents(order.getCreditTotal() * 1_000L)
            .status(status)
            .build());
    }

    @Test
    @DisplayName("addItems(): rejects a DIRECT-paid order with 409 order-not-modifiable — nothing changes")
    void addItemsRechazaPedidoPagadoDirecto() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dishA.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        Order order = orderRepo.findById(placed.id()).orElseThrow();
        persistDirectPurchase(user, order, CreditPurchaseStatus.PENDING);

        assertThatThrownBy(() -> orderPlacementService.addItems(user.getId(), placed.id(), addItemsRequest(dishB.getId())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-modifiable");

        entityManager.clear();
        assertThat(dishRepo.findById(dishB.getId()).orElseThrow().getStockActual()).isEqualTo(5);
        assertThat(orderRepo.findById(placed.id()).orElseThrow().getCreditTotal()).isEqualTo(2);
        assertThat(orderRepo.findById(placed.id()).orElseThrow().getItems()).hasSize(1);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(8);
        assertThat(wallet.getCommitted()).isEqualTo(2);
    }

    @Test
    @DisplayName("removeItem(): rejects a DIRECT-paid order with 409 order-not-modifiable — nothing changes")
    void removeItemRechazaPedidoPagadoDirecto() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        Order order = orderRepo.findById(placed.id()).orElseThrow();
        persistDirectPurchase(user, order, CreditPurchaseStatus.PENDING);
        Long itemId = placed.items().get(0).id();

        assertThatThrownBy(() -> orderPlacementService.removeItem(user.getId(), placed.id(), itemId))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-modifiable");

        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(4);
        assertThat(orderRepo.findById(placed.id()).orElseThrow().getItems()).hasSize(1);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(8);
        assertThat(wallet.getCommitted()).isEqualTo(2);
    }

    @Test
    @DisplayName("addItems(): an APPROVED DIRECT purchase also rejects modification of its order")
    void addItemsRechazaPedidoPagadoDirectoYaAprobado() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dishA.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        Order order = orderRepo.findById(placed.id()).orElseThrow();
        persistDirectPurchase(user, order, CreditPurchaseStatus.APPROVED);

        assertThatThrownBy(() -> orderPlacementService.addItems(user.getId(), placed.id(), addItemsRequest(dishB.getId())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-modifiable");
    }

    // ─── B6.1 (revisión): 400 en POST /items con lista vacía o nula ────────
    //
    // Patrón más liviano que ya usa el repo (ver
    // CreditPurchaseServiceTest#quantityFueraDeRangoFallaLaValidacionDelBean):
    // validar el bean directo con el Validator de Bean Validation en vez de
    // un test @WebMvcTest completo — AddOrderItemsRequest.items() ya lleva
    // @NotEmpty, y GlobalExceptionHandler ya mapea
    // MethodArgumentNotValidException a 400 para cualquier @Valid @RequestBody.

    @Test
    @DisplayName("AddOrderItemsRequest: una lista de ítems vacía o nula falla la validación del bean (→ 400 vía @Valid)")
    void addOrderItemsRequestConListaVaciaOInexistenteFallaLaValidacion() {
        AddOrderItemsRequest vacio = new AddOrderItemsRequest(List.of());
        assertThat(validator.validate(vacio)).isNotEmpty();

        AddOrderItemsRequest nulo = new AddOrderItemsRequest(null);
        assertThat(validator.validate(nulo)).isNotEmpty();

        AddOrderItemsRequest valido = new AddOrderItemsRequest(
            List.of(new PlaceOrderV2Request.OrderItemRequest(1L, null, null)));
        assertThat(validator.validate(valido)).isEmpty();
    }

    // ─── B10: `modifiable` y `pickupTimeChangeable` en el DTO ──────────────
    //
    // El frontend decidía "agregar platos a este pedido" con `cancellable`,
    // que también es true para PENDIENTE_PAGO y para pedidos pagados aparte
    // (DIRECT) — el backend después los rechazaba con 409. `modifiable` sale
    // del MISMO predicado que assertModifiable.

    @Test
    @DisplayName("list(): modifiable is true for a normal scheduled order before the cutoff")
    void listMarcaModifiableUnPedidoProgramadoNormal() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto placed = orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));

        assertThat(placed.modifiable()).isTrue();
        OrderDto listed = orderPlacementService.list(user.getId()).get(0);
        assertThat(listed.modifiable()).isTrue();
        assertThat(listed.pickupTimeChangeable()).isTrue();
    }

    @Test
    @DisplayName("list(): modifiable is false for PENDIENTE_PAGO even though it is cancellable")
    void listNoMarcaModifiableUnPedidoEsperandoPago() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();

        Order awaiting = orderPlacementService.placeAwaitingPayment(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));

        OrderDto listed = orderPlacementService.list(user.getId()).stream()
            .filter(o -> o.id().equals(awaiting.getId())).findFirst().orElseThrow();
        assertThat(listed.cancellable()).isTrue();
        assertThat(listed.modifiable()).isFalse();
        assertThat(listed.pickupTimeChangeable()).isFalse();
    }

    @Test
    @DisplayName("list(): modifiable is false for a DIRECT-paid order but its pickup time can still change")
    void listNoMarcaModifiableUnPedidoPagadoDirecto() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto direct = orderPlacementService.place(user.getId(),
            singleItemRequest(dishA.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        persistDirectPurchase(user, orderRepo.findById(direct.id()).orElseThrow(), CreditPurchaseStatus.PENDING);
        OrderDto normal = orderPlacementService.place(user.getId(),
            singleItemRequest(dishB.getId(), FIXED_NOW.plus(120, ChronoUnit.MINUTES)));

        List<OrderDto> orders = orderPlacementService.list(user.getId());
        OrderDto foundDirect = orders.stream().filter(o -> o.id().equals(direct.id())).findFirst().orElseThrow();
        OrderDto foundNormal = orders.stream().filter(o -> o.id().equals(normal.id())).findFirst().orElseThrow();

        assertThat(foundDirect.modifiable()).isFalse();
        assertThat(foundDirect.pickupTimeChangeable()).isTrue();
        // El bulk de DIRECT no contamina al resto de los pedidos del listado.
        assertThat(foundNormal.modifiable()).isTrue();
    }

    @Test
    @DisplayName("list(): modifiable and pickupTimeChangeable are false after the cutoff and for CONFIRMADO")
    void listNoMarcaModifiableDespuesDelCorteNiConfirmado() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);

        OrderDto atCutoff = orderPlacementService.place(user.getId(),
            singleItemRequest(dishA.getId(), FIXED_NOW.plus(20, ChronoUnit.MINUTES)));
        OrderDto confirmed = orderPlacementService.place(user.getId(),
            singleItemRequest(dishB.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));
        Order confirmedOrder = orderRepo.findById(confirmed.id()).orElseThrow();
        confirmedOrder.setEstado(OrderEstado.CONFIRMADO);
        orderRepo.save(confirmedOrder);

        List<OrderDto> orders = orderPlacementService.list(user.getId());
        OrderDto foundCutoff = orders.stream().filter(o -> o.id().equals(atCutoff.id())).findFirst().orElseThrow();
        OrderDto foundConfirmed = orders.stream().filter(o -> o.id().equals(confirmed.id())).findFirst().orElseThrow();

        assertThat(foundCutoff.modifiable()).isFalse();
        assertThat(foundCutoff.pickupTimeChangeable()).isFalse();
        assertThat(foundConfirmed.modifiable()).isFalse();
        assertThat(foundConfirmed.pickupTimeChangeable()).isFalse();
    }

    // ─── B11: cambiar el horario de retiro ─────────────────────────────────
    //
    // FIXED_NOW = martes 10:40 ART, ventana 11:00-15:00, paso de 10 min, lead
    // de 20 min. +90 min = 12:10, +120 min = 12:40.

    /** El controller tiene @PreAuthorize: hace falta un usuario autenticado en el contexto. */
    private void authenticateAs(User user) {
        JwtUser principal = new JwtUser(user.getId(), user.getEmail(), Role.EMPLOYEE, null, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private OrderDto placeScheduled(User user, Dish dish, long minutesFromNow) {
        return orderPlacementService.place(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(minutesFromNow, ChronoUnit.MINUTES)));
    }

    private void assertPickupChangeRejected(User user, Long orderId, Instant newPickupAt, String errorCode) {
        assertThatThrownBy(() -> orderPlacementService.changePickupTime(user.getId(), orderId, newPickupAt))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", errorCode);
    }

    @Test
    @DisplayName("changePickupTime(): moves a scheduled order within the same day, keeps fecha, resets reminderSentAt and touches no stock/credits")
    void changePickupTimeMueveElHorarioDentroDelMismoDia() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);
        OrderDto placed = placeScheduled(user, dish, 90);
        Order order = orderRepo.findById(placed.id()).orElseThrow();
        order.setReminderSentAt(FIXED_NOW);
        orderRepo.save(order);
        Instant newPickup = FIXED_NOW.plus(120, ChronoUnit.MINUTES);

        OrderDto changed = orderPlacementService.changePickupTime(user.getId(), placed.id(), newPickup);

        assertThat(changed.pickupAt()).isEqualTo(newPickup);
        assertThat(changed.fecha()).isEqualTo(placed.fecha());
        assertThat(changed.estado()).isEqualTo(OrderEstado.PENDIENTE);
        assertThat(changed.modifiable()).isTrue();
        assertThat(changed.pickupTimeChangeable()).isTrue();
        entityManager.flush();
        entityManager.clear();
        Order reloaded = orderRepo.findById(placed.id()).orElseThrow();
        assertThat(reloaded.getPickupAt()).isEqualTo(newPickup);
        assertThat(reloaded.getReminderSentAt()).isNull();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(4);
        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(8);
        assertThat(wallet.getCommitted()).isEqualTo(2);
    }

    @Test
    @DisplayName("changePickupTime(): the same time is a harmless no-op that keeps reminderSentAt")
    void changePickupTimeConElMismoHorarioEsNoOp() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);
        OrderDto placed = placeScheduled(user, dish, 90);
        Order order = orderRepo.findById(placed.id()).orElseThrow();
        order.setReminderSentAt(FIXED_NOW);
        orderRepo.save(order);

        OrderDto same = orderPlacementService.changePickupTime(user.getId(), placed.id(), placed.pickupAt());

        assertThat(same.pickupAt()).isEqualTo(placed.pickupAt());
        assertThat(orderRepo.findById(placed.id()).orElseThrow().getReminderSentAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("changePickupTime(): a DIRECT-paid order can change its time (the amount does not change)")
    void changePickupTimeAceptaUnPedidoPagadoDirecto() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);
        OrderDto placed = placeScheduled(user, dish, 90);
        persistDirectPurchase(user, orderRepo.findById(placed.id()).orElseThrow(), CreditPurchaseStatus.PENDING);
        Instant newPickup = FIXED_NOW.plus(120, ChronoUnit.MINUTES);

        OrderDto changed = orderPlacementService.changePickupTime(user.getId(), placed.id(), newPickup);

        assertThat(changed.pickupAt()).isEqualTo(newPickup);
        assertThat(changed.modifiable()).isFalse();
    }

    @Test
    @DisplayName("changePickupTime(): rejects a CONFIRMADO order with 409 pickup-time-locked and changes nothing")
    void changePickupTimeRechazaPedidoConfirmado() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);
        OrderDto placed = placeScheduled(user, dish, 90);
        Order order = orderRepo.findById(placed.id()).orElseThrow();
        order.setEstado(OrderEstado.CONFIRMADO);
        orderRepo.save(order);

        assertPickupChangeRejected(user, placed.id(), FIXED_NOW.plus(120, ChronoUnit.MINUTES), "pickup-time-locked");

        assertThat(orderRepo.findById(placed.id()).orElseThrow().getPickupAt()).isEqualTo(placed.pickupAt());
    }

    @Test
    @DisplayName("changePickupTime(): rejects a PENDIENTE_PAGO order with 409 pickup-time-locked")
    void changePickupTimeRechazaPedidoEsperandoPago() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        Order awaiting = orderPlacementService.placeAwaitingPayment(user.getId(),
            singleItemRequest(dish.getId(), FIXED_NOW.plus(90, ChronoUnit.MINUTES)));

        assertPickupChangeRejected(user, awaiting.getId(), FIXED_NOW.plus(120, ChronoUnit.MINUTES), "pickup-time-locked");
    }

    @Test
    @DisplayName("changePickupTime(): rejects a CANCELADO order with 409 pickup-time-locked")
    void changePickupTimeRechazaPedidoCancelado() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);
        OrderDto placed = placeScheduled(user, dish, 90);
        orderPlacementService.cancel(user.getId(), placed.id());

        assertPickupChangeRejected(user, placed.id(), FIXED_NOW.plus(120, ChronoUnit.MINUTES), "pickup-time-locked");
    }

    @Test
    @DisplayName("changePickupTime(): rejects once the CURRENT pickup's cutoff has passed")
    void changePickupTimeRechazaDespuesDelCorteDelHorarioActual() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);
        // Corte exactamente "ahora" (pickupAt - 20 min == FIXED_NOW).
        OrderDto placed = placeScheduled(user, dish, 20);

        assertPickupChangeRejected(user, placed.id(), FIXED_NOW.plus(120, ChronoUnit.MINUTES), "pickup-time-locked");
    }

    @Test
    @DisplayName("changePickupTime(): rejects a different day with pickup-day-change-not-allowed")
    void changePickupTimeRechazaOtroDia() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);
        OrderDto placed = placeScheduled(user, dish, 90);

        // Mañana a la misma hora: válido como horario, pero es OTRO día (otro menú y stock).
        assertPickupChangeRejected(user, placed.id(),
            placed.pickupAt().plus(1, ChronoUnit.DAYS), "pickup-day-change-not-allowed");

        assertThat(orderRepo.findById(placed.id()).orElseThrow().getPickupAt()).isEqualTo(placed.pickupAt());
    }

    @Test
    @DisplayName("changePickupTime(): the new time goes through PickupSlotService — misaligned and outside the window are rejected")
    void changePickupTimeValidaElHorarioNuevoConPickupSlotService() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);
        OrderDto placed = placeScheduled(user, dish, 90);

        // 12:15 — no cae en un slot de 10 minutos desde las 11:00.
        assertPickupChangeRejected(user, placed.id(),
            FIXED_NOW.plus(95, ChronoUnit.MINUTES), "pickup-time-not-aligned");
        // 10:50 — antes de que abra la ventana (11:00).
        assertPickupChangeRejected(user, placed.id(),
            FIXED_NOW.plus(10, ChronoUnit.MINUTES), "pickup-outside-service-window");
        // 15:00 — la ventana es [11:00, 15:00).
        assertPickupChangeRejected(user, placed.id(),
            FIXED_NOW.plus(260, ChronoUnit.MINUTES), "pickup-outside-service-window");

        assertThat(orderRepo.findById(placed.id()).orElseThrow().getPickupAt()).isEqualTo(placed.pickupAt());
    }

    @Test
    @DisplayName("changePickupTime(): someone else's order is 404 order-not-found")
    void changePickupTimeRechazaSiNoEsElDueño() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User owner = persistB2cUser();
        User stranger = persistB2cUser();
        seedWallet(owner.getId(), 10);
        OrderDto placed = placeScheduled(owner, dish, 90);

        assertPickupChangeRejected(stranger, placed.id(), FIXED_NOW.plus(120, ChronoUnit.MINUTES), "order-not-found");
        assertThat(orderRepo.findById(placed.id()).orElseThrow().getPickupAt()).isEqualTo(placed.pickupAt());
    }

    @Test
    @DisplayName("changePickupTime(): a null time is a 400 pickup-at-required")
    void changePickupTimeRechazaHorarioNulo() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);
        OrderDto placed = placeScheduled(user, dish, 90);

        assertPickupChangeRejected(user, placed.id(), null, "pickup-at-required");
    }

    @Test
    @DisplayName("changePickupTime(): moving onto a time where another order exists keeps them as two separate orders")
    void changePickupTimeAMismaHoraDeOtroPedidoNoLosFusiona() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dishA = persistDish(category, section, 5);
        Dish dishB = persistDish(category, section, 5);
        User user = persistB2cUser();
        seedWallet(user.getId(), 10);
        OrderDto first = placeScheduled(user, dishA, 90);
        OrderDto second = placeScheduled(user, dishB, 120);

        orderPlacementService.changePickupTime(user.getId(), second.id(), first.pickupAt());

        List<Order> orders = orderRepo.findByUserIdAndFecha(user.getId(), first.fecha());
        assertThat(orders).hasSize(2);
        assertThat(orders).allSatisfy(o -> assertThat(o.getPickupAt()).isEqualTo(first.pickupAt()));
    }

    @Test
    @DisplayName("ChangePickupTimeRequest: a null pickupAt fails bean validation (→ 400 via @Valid)")
    void changePickupTimeRequestSinHorarioFallaLaValidacion() {
        assertThat(validator.validate(new ChangePickupTimeRequest(null))).isNotEmpty();
        assertThat(validator.validate(new ChangePickupTimeRequest(FIXED_NOW))).isEmpty();
    }

    @Test
    @DisplayName("PATCH /api/v2/orders/{id}/pickup-time (controller): scopes by the authenticated user and returns the updated order")
    void controllerCambiaElHorarioDelPedidoDelUsuarioAutenticado() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section, 5);
        User owner = persistB2cUser();
        User stranger = persistB2cUser();
        seedWallet(owner.getId(), 10);
        OrderDto placed = placeScheduled(owner, dish, 90);
        Instant newPickup = FIXED_NOW.plus(120, ChronoUnit.MINUTES);

        authenticateAs(owner);
        OrderDto changed = orderPlacementController.changePickupTime(
            new JwtUser(owner.getId(), owner.getEmail(), Role.EMPLOYEE, null, null),
            placed.id(), new ChangePickupTimeRequest(newPickup));

        assertThat(changed.id()).isEqualTo(placed.id());
        assertThat(changed.pickupAt()).isEqualTo(newPickup);
        authenticateAs(stranger);
        assertThatThrownBy(() -> orderPlacementController.changePickupTime(
            new JwtUser(stranger.getId(), stranger.getEmail(), Role.EMPLOYEE, null, null),
            placed.id(), new ChangePickupTimeRequest(FIXED_NOW.plus(100, ChronoUnit.MINUTES))))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-found");
    }
}
