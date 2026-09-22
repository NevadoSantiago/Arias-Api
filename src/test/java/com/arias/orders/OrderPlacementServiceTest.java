package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

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

    static final Instant FIXED_NOW = Instant.parse("2026-03-10T12:00:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

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

    private User persistB2cUser() {
        User user = User.builder()
            .email("b2c-" + System.nanoTime() + "@test.arias.com")
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
}
