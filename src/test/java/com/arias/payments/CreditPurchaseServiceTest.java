package com.arias.payments;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.credits.CreditMovement;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.MovementType;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderItem;
import com.arias.orders.OrderPlacementService;
import com.arias.orders.OrderRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CreditPurchaseService#createPurchase} — unidad 11, tarea 11.3: el
 * importe SIEMPRE se calcula en el servidor (diseño §Seguridad), nunca lo
 * manda el cliente. {@link PaymentGateway} queda mockeado — ninguna llamada
 * real a Mercado Pago.
 */
@SpringBootTest
@Transactional
class CreditPurchaseServiceTest {

    @Autowired
    private CreditPurchaseService purchaseService;

    @Autowired
    private CreditPurchaseRepository purchaseRepo;

    @Autowired
    private CreditPackRepository packRepo;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private OrderPlacementService orderPlacementService;

    @Autowired
    private PlatformTransactionManager txManager;

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
    private EntityManager entityManager;

    @MockitoBean
    private PaymentGateway paymentGateway;

    @Autowired
    private jakarta.validation.Validator validator;

    // System.nanoTime() no alcanza para distinguir dos persistUser() seguidos
    // dentro del mismo test (colisiona en uq_users_phone) — un contador
    // monotónico sí garantiza unicidad.
    private static final AtomicLong PHONE_SEQ = new AtomicLong();

    /**
     * Verificado y con perfil completo (teléfono + apodo) por defecto — el
     * caso feliz que usa el resto de la suite (refleja el autorregistro
     * real, donde {@code RegisterRequest} exige ambos campos).
     */
    private User persistUser(String prefix) {
        User user = User.builder()
            .email(prefix + "-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone("+549" + (1133440100L + PHONE_SEQ.incrementAndGet()))
            .nickname("Apodo-" + System.nanoTime())
            .build();
        return userRepo.save(user);
    }

    /**
     * Correo verificado (típico de login con Google, diseño §Decisión 9)
     * pero sin teléfono y/o apodo — todavía no pasó por {@code
     * complete-profile}. Gap de la unidad 9.
     */
    private User persistUserConPerfilIncompleto(String prefix, String phone, String nickname) {
        User user = User.builder()
            .email(prefix + "-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone(phone)
            .nickname(nickname)
            .build();
        return userRepo.save(user);
    }

    /** B2C autorregistrado que todavía no verificó el correo — gap de la unidad 4/5. */
    private User persistUnverifiedUser(String prefix) {
        User user = User.builder()
            .email(prefix + "-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .build();
        return userRepo.save(user);
    }

    /** Empleado de empresa con {@code email_verified_at = NULL} — regresión V16. */
    private User persistCompanyEmployeeWithNullEmailVerifiedAt() {
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
        Company company = companyRepo.save(Company.builder()
            .nombre("Empresa-" + System.nanoTime())
            .cuit(String.valueOf(20_000_000_000L + (System.nanoTime() % 9_000_000_000L)))
            .calle("Calle Falsa")
            .altura("123")
            .horaEntrega(java.time.LocalTime.of(13, 0))
            .categoriaDefault(category)
            .enabled(true)
            .build());
        User user = User.builder()
            .email("empleado-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .company(company)
            .category(category)
            .active(true)
            .build();
        return userRepo.save(user);
    }

    @Test
    void compraDePaqueteCalculaElImporteDesdePriceCentsDelPaquete() {
        User user = persistUser("pack-amount");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8))
            .nombre("Semana")
            .creditAmount(20)
            .priceCents(45_000L)
            .discountPercent(10)
            .ordenDisplay(0)
            .enabled(true)
            .build());

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-1", "https://mp.test/init"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null));

        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getCreditAmount()).isEqualTo(20);
        assertThat(purchase.getAmountCents()).isEqualTo(45_000L);
        assertThat(purchase.getStatus()).isEqualTo(CreditPurchaseStatus.PENDING);
        assertThat(purchase.getMpPreferenceId()).isEqualTo("pref-1");
        assertThat(dto.initPoint()).isEqualTo("https://mp.test/init");

        // El importe pasado a Mercado Pago es el de la base, nunca uno inventado por el cliente.
        verify(paymentGateway).createCheckout(argThat(req -> req.unitPriceCents() == 45_000L
            && req.quantity() == 1
            && req.externalReference().equals(purchase.getId().toString())));
    }

    // ─── quantity en la compra de PACK — sueltos sobre el pack DAY ──────────
    // (decisión de usuario 2026-09-25, feature b2c-ordering-redesign, tarea B2)

    @Test
    @DisplayName("compra de PACK con quantity multiplica créditos e importe, y la línea de Mercado Pago lleva esa cantidad")
    void compraDePaqueteConCantidadMultiplicaCreditosImporteYLineaDeMercadoPago() {
        User user = persistUser("pack-quantity");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("DAY-" + java.util.UUID.randomUUID().toString().substring(0, 8))
            .nombre("Día")
            .creditAmount(2)
            .priceCents(3_000L)
            .discountPercent(0)
            .ordenDisplay(0)
            .enabled(true)
            .build());

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-quantity", "https://mp.test/init"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, 3));

        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getCreditAmount()).isEqualTo(6); // 2 * 3
        assertThat(purchase.getAmountCents()).isEqualTo(9_000L); // 3000 * 3

        // El total de Mercado Pago (quantity * unitPriceCents) debe coincidir
        // con amountCents — nunca un importe distinto al calculado en el servidor.
        verify(paymentGateway).createCheckout(argThat(req -> req.quantity() == 3
            && req.unitPriceCents() == 3_000L));
    }

    @Test
    @DisplayName("compra de PACK sin quantity equivale a 1 (comportamiento previo a B2)")
    void compraDePaqueteSinCantidadEquivaleAUno() {
        User user = persistUser("pack-quantity-null");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("DAY-" + java.util.UUID.randomUUID().toString().substring(0, 8))
            .nombre("Día")
            .creditAmount(2)
            .priceCents(3_000L)
            .discountPercent(0)
            .ordenDisplay(0)
            .enabled(true)
            .build());

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-quantity-null", "https://mp.test/init"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null));

        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getCreditAmount()).isEqualTo(2);
        assertThat(purchase.getAmountCents()).isEqualTo(3_000L);
        verify(paymentGateway).createCheckout(argThat(req -> req.quantity() == 1
            && req.unitPriceCents() == 3_000L));
    }

    // ─── Cierre del camino DIRECT viejo (feature b2c-ordering-redesign, ────
    // ─── unidad B7): createPurchase(DIRECT, ...) exigía un pedido PENDIENTE ─
    // ─── ya creado, pero crear ese pedido con OrderPlacementService.place() ─
    // ─── YA comprometía créditos — pagarlo "directo" después cobraba dos ────
    // ─── veces (bug verificado: committed pasaba de 2 a 4, ver el test ──────
    // ─── (ahora eliminado) approvedDirectPurchaseLeavesItsOrderAlone más ────
    // ─── abajo, reemplazado por directCheckoutApprovalMovesOrderToPendiente ─
    // ─── AndCommitsCreditsOnce). El pago directo ahora nace ATADO a su ──────
    // ─── propio pedido sin comprometer saldo — ver DirectCheckoutServiceTest.

    @Test
    @DisplayName("createPurchase(DIRECT, ...) se rechaza siempre — el pago directo ahora nace en /direct-checkout")
    void createPurchaseDirectSeRechazaSiempre() {
        User user = persistUser("direct-closed");

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.DIRECT, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "direct-purchase-not-supported");

        assertThat(purchaseRepo.findAll()).isEmpty();
        verify(paymentGateway, org.mockito.Mockito.never()).createCheckout(any());
    }

    @Test
    @DisplayName("quantity fuera de rango (0 u 11) falla la validación del bean del request")
    void quantityFueraDeRangoFallaLaValidacionDelBean() {
        CreatePurchaseRequest cero = new CreatePurchaseRequest(PurchaseType.PACK, 1L, null, 0);
        assertThat(validator.validate(cero)).isNotEmpty();

        CreatePurchaseRequest once = new CreatePurchaseRequest(PurchaseType.PACK, 1L, null, 11);
        assertThat(validator.validate(once)).isNotEmpty();

        CreatePurchaseRequest valido = new CreatePurchaseRequest(PurchaseType.PACK, 1L, null, 10);
        assertThat(validator.validate(valido)).isEmpty();
    }

    @Test
    @DisplayName("applySnapshot(): una compra PACK aprobada con quantity acredita el total multiplicado y renueva el vencimiento")
    void compraDePaqueteAprobadaConCantidadAcreditaElTotalMultiplicadoYRenuevaVencimiento() {
        User user = persistUser("pack-quantity-approved");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("DAY-" + java.util.UUID.randomUUID().toString().substring(0, 8))
            .nombre("Día")
            .creditAmount(2)
            .priceCents(3_000L)
            .discountPercent(0)
            .ordenDisplay(0)
            .enabled(true)
            .build());

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-quantity-approved", "https://mp.test/init"));
        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, 3));
        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();

        purchaseService.applySnapshot(new PaymentSnapshot("mp-quantity-approved", PaymentStatus.APPROVED,
            "accredited", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));

        entityManager.flush();
        entityManager.clear();

        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.APPROVED);

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(6); // 2 * 3, van a disponibles (no committed)
        assertThat(wallet.getCommitted()).isZero();
        assertThat(wallet.getExpiresAt()).isNotNull().isAfter(Instant.now());
    }

    // ─── Gate de verificación de correo (gap de la unidad 4/5, no de login) ─

    @Test
    void compraSeRechazaSiElB2cNoVerificoElCorreo() {
        User user = persistUnverifiedUser("sin-verificar");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8)).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "email-not-verified");

        assertThat(purchaseRepo.findAll()).isEmpty();
        verify(paymentGateway, org.mockito.Mockito.never()).createCheckout(any());
    }

    @Test
    void compraSeAceptaSiElB2cVerificoElCorreo() {
        User user = persistUser("verificado");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8)).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-verificado", "https://mp.test/init"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null));

        assertThat(dto.purchaseId()).isNotNull();
    }

    @Test
    void compraNoSeBloqueaParaEmpleadoDeEmpresaConEmailVerifiedAtNulo() {
        User employee = persistCompanyEmployeeWithNullEmailVerifiedAt();
        assertThat(employee.getEmailVerifiedAt()).isNull(); // exactamente el caso que V16 no pudo rellenar
        assertThat(employee.getPhone()).isNull(); // el alta por lista blanca nunca captura teléfono ni apodo
        assertThat(employee.getNickname()).isNull();
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8)).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-empleado", "https://mp.test/init"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(employee.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null));

        assertThat(dto.purchaseId()).isNotNull();
    }

    // ─── Gate de perfil incompleto (gap de la unidad 9, diseño §Decisión 9) ─

    @Test
    void compraSeRechazaSiElB2cNoTieneTelefono() {
        User user = persistUserConPerfilIncompleto("sin-telefono", null, "Apodo-" + System.nanoTime());
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8)).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "profile-incomplete");

        assertThat(purchaseRepo.findAll()).isEmpty();
        verify(paymentGateway, org.mockito.Mockito.never()).createCheckout(any());
    }

    @Test
    void compraSeRechazaSiElB2cNoTieneApodo() {
        User user = persistUserConPerfilIncompleto("sin-apodo",
            "+549" + (1133440200L + PHONE_SEQ.incrementAndGet()), null);
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8)).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "profile-incomplete");
    }

    @Test
    void compraSeAceptaSiElB2cTienePerfilCompleto() {
        User user = persistUser("perfil-completo");
        assertThat(user.getPhone()).isNotBlank();
        assertThat(user.getNickname()).isNotBlank();
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8)).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-perfil-completo", "https://mp.test/init"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null));

        assertThat(dto.purchaseId()).isNotNull();
    }

    @Test
    void compraConAmbosGatesDisparablesMuestraEmailNotVerifiedPrimero() {
        // Sin verificar Y sin teléfono/apodo — ambos gates aplicarían.
        User user = persistUnverifiedUser("ambos-gates");
        assertThat(user.getPhone()).isNull();
        assertThat(user.getNickname()).isNull();
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8)).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "email-not-verified");
    }

    private static CheckoutRequest argThat(java.util.function.Predicate<CheckoutRequest> predicate) {
        return org.mockito.Mockito.argThat(predicate::test);
    }

    // ─── Gap fix: a rejected/cancelled/expired DIRECT purchase closes its ───
    // ─── order (design.md §Flujo de datos, "rejected/cancelled" row) ────────
    // ─── Rewritten for unidad B7 (feature b2c-ordering-redesign): DIRECT ────
    // ─── purchases no longer attach to an already-PENDIENTE order (that ─────
    // ─── path is closed above) — they attach to a fresh PENDIENTE_PAGO ──────
    // ─── order that never committed credits. The order/dish state below is ─
    // ─── built directly instead of going through
    // ─── OrderPlacementService.placeAwaitingPayment() (which requires a
    // ─── valid pickup time inside the restaurant_config service window
    // ─── against the real system clock) — it reproduces exactly the state
    // ─── that call would leave behind (stock already decremented, no wallet
    // ─── movement at all). Pickup-window validation and the full
    // ─── direct-checkout flow are covered by DirectCheckoutServiceTest.

    private Dish persistDishWithStock(int stock) {
        Category category = categoryRepo.save(Category.builder()
            .nombre("Category-" + System.nanoTime()).ordenDisplay(0).enabled(true).creditCost(1).build());
        MenuSection section = menuSectionRepo.save(MenuSection.builder()
            .nombre("Section-" + System.nanoTime()).ordenDisplay(0).enabled(true).build());
        return dishRepo.save(Dish.builder()
            .nombre("Dish-" + System.nanoTime())
            .category(category)
            .menuSection(section)
            .enabled(true)
            .especial(false)
            .stockDiarioDefault(stock)
            .stockActual(stock)
            .build());
    }

    /**
     * Builds an {@link Order} in exactly the state {@code
     * OrderPlacementService.placeAwaitingPayment()} would leave it (unidad
     * B7): stock already decremented for its single item, {@code estado =
     * PENDIENTE_PAGO}, and NO wallet movement at all — a DIRECT checkout
     * never commits credits up front.
     */
    private Order persistOrderAwaitingPayment(User user, Dish dish, int creditTotal) {
        dishRepo.decrementStock(dish.getId());

        Order order = Order.builder()
            .user(user)
            .fecha(LocalDate.now())
            .pickupAt(Instant.now().plus(2, ChronoUnit.HOURS))
            .estado(OrderEstado.PENDIENTE_PAGO)
            .creditTotal(creditTotal)
            .build();
        order.addItem(OrderItem.builder()
            .dish(dish)
            .dishNombre(dish.getNombre())
            .dishCategoria(dish.getCategory().getNombre())
            .creditCost(creditTotal)
            .build());
        return orderRepo.save(order);
    }

    /**
     * Builds a {@code PENDING} DIRECT {@link CreditPurchase} tied to {@code
     * order}, exactly as {@code CreditPurchaseService#createDirectCheckout}
     * would leave it (minus {@code mpPreferenceId}/{@code initPoint}, not
     * needed by the payment-outcome tests below).
     */
    private CreditPurchase persistDirectPurchase(User user, Order order, int creditAmount, long amountCents) {
        return purchaseRepo.save(CreditPurchase.builder()
            .user(user)
            .type(PurchaseType.DIRECT)
            .order(order)
            .creditAmount(creditAmount)
            .amountCents(amountCents)
            .currency("ARS")
            .status(CreditPurchaseStatus.PENDING)
            .build());
    }

    @Test
    @DisplayName("applySnapshot(): a rejected DIRECT purchase leaves its order CANCELADO, with stock restored and NO credits touched")
    void rejectedDirectPurchaseClosesItsOrder() {
        User user = persistUser("reject-direct");
        Dish dish = persistDishWithStock(5);
        Order order = persistOrderAwaitingPayment(user, dish, 2);
        CreditPurchase purchase = persistDirectPurchase(user, order, 2, 2_000L);

        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(4);

        purchaseService.applySnapshot(new PaymentSnapshot("mp-reject-1", PaymentStatus.REJECTED,
            "cc_rejected_other_reason", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));

        entityManager.flush();
        entityManager.clear();

        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.REJECTED);

        Order closed = orderRepo.findById(order.getId()).orElseThrow();
        assertThat(closed.getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(closed.getCancelledAt()).isNotNull();

        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(5);

        // No credits were ever committed for a PENDIENTE_PAGO order, so the
        // rejection touches no wallet at all — unlike the old (now closed)
        // DIRECT-on-a-PENDIENTE-order path, which released committed credits.
        CreditWallet wallet = walletRepo.findById(user.getId()).orElseGet(() -> CreditWallet.emptyFor(user.getId()));
        assertThat(wallet.getAvailable()).isZero();
        assertThat(wallet.getCommitted()).isZero();
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("applySnapshot(): processing the same rejection twice changes nothing the second time")
    void repeatedRejectionIsIdempotent() {
        User user = persistUser("reject-direct-dup");
        Dish dish = persistDishWithStock(5);
        Order order = persistOrderAwaitingPayment(user, dish, 2);
        CreditPurchase purchase = persistDirectPurchase(user, order, 2, 2_000L);

        PaymentSnapshot rejected = new PaymentSnapshot("mp-reject-dup", PaymentStatus.REJECTED,
            "cc_rejected_other_reason", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L);

        purchaseService.applySnapshot(rejected);
        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(5);

        // Same webhook notification delivered a second time (Mercado Pago
        // retries, or the reconciliation job re-processes it).
        purchaseService.applySnapshot(rejected);
        entityManager.flush();
        entityManager.clear();

        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(5);
        assertThat(orderRepo.findById(order.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("expirePendingPurchase(): an expired DIRECT purchase closes its order the same way a rejection does")
    void expiredDirectPurchaseClosesItsOrder() {
        User user = persistUser("expire-direct");
        Dish dish = persistDishWithStock(4);
        Order order = persistOrderAwaitingPayment(user, dish, 3);
        CreditPurchase purchase = persistDirectPurchase(user, order, 3, 3_000L);

        // Reached via PaymentReconciliationScheduler after 24h without a
        // reported payment — never through the webhook.
        purchaseService.expirePendingPurchase(purchase.getId());

        entityManager.flush();
        entityManager.clear();

        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.EXPIRED);
        assertThat(orderRepo.findById(order.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(4);
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("applySnapshot(): a rejected PACK purchase touches no order")
    void rejectedPackPurchaseTouchesNoOrder() {
        User user = persistUser("reject-pack");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8)).nombre("Semana").creditAmount(10)
            .priceCents(10_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-reject-pack", "https://mp.test/init"));
        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null));
        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getOrder()).isNull();

        purchaseService.applySnapshot(new PaymentSnapshot("mp-reject-pack", PaymentStatus.REJECTED,
            "cc_rejected_other_reason", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));

        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.REJECTED);
        assertThat(orderRepo.findAll()).isEmpty();
        CreditWallet wallet = walletRepo.findById(user.getId()).orElseGet(() -> CreditWallet.emptyFor(user.getId()));
        assertThat(wallet.getAvailable()).isZero();
        assertThat(wallet.getCommitted()).isZero();
    }

    @Test
    @DisplayName("applySnapshot(): DIRECT approval moves the order PENDIENTE_PAGO -> PENDIENTE and commits credits exactly once (not doubled)")
    void directCheckoutApprovalMovesOrderToPendienteAndCommitsCreditsOnce() {
        User user = persistUser("approve-direct");
        Dish dish = persistDishWithStock(5);
        Order order = persistOrderAwaitingPayment(user, dish, 2);
        CreditPurchase purchase = persistDirectPurchase(user, order, 2, 2_000L);

        purchaseService.applySnapshot(new PaymentSnapshot("mp-approve-1", PaymentStatus.APPROVED,
            "accredited", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));

        entityManager.flush();
        entityManager.clear();

        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.APPROVED);

        // This is the fix for the double-charge bug (verified before unidad
        // B7): the order becomes a normal scheduled order, and its credits
        // are committed by THIS approval — never by place(), which the
        // direct-checkout flow no longer calls.
        Order activated = orderRepo.findById(order.getId()).orElseThrow();
        assertThat(activated.getEstado()).isEqualTo(OrderEstado.PENDIENTE);
        assertThat(activated.getCancelledAt()).isNull();

        // Stock stays as it was reserved by placeAwaitingPayment — approval
        // never touches stock, only the ledger.
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(4);

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        // NOT doubled: committed == creditTotal (2), not 4.
        assertThat(wallet.getCommitted()).isEqualTo(2);
        assertThat(wallet.getAvailable()).isZero();

        List<CreditMovement> movements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getType()).isEqualTo(MovementType.DIRECT_PURCHASE);
        assertThat(movements.get(0).getDeltaCommitted()).isEqualTo(2);
    }

    @Test
    @DisplayName("applySnapshot(): DIRECT approval after the customer already cancelled the order credits AVAILABLE instead, and never touches the cancelled order")
    void directCheckoutApprovalAfterOrderAlreadyCancelledCreditsAvailable() {
        User user = persistUser("approve-after-cancel");
        Dish dish = persistDishWithStock(5);
        Order order = persistOrderAwaitingPayment(user, dish, 2);
        CreditPurchase purchase = persistDirectPurchase(user, order, 2, 2_000L);

        // Customer (or the pickup-cutoff scheduler) cancelled the order while
        // the payment was still in flight — restoring stock, exactly as
        // OrderPlacementService.applyCancellation() would for PENDIENTE_PAGO.
        dishRepo.incrementStock(dish.getId());
        order.setEstado(OrderEstado.CANCELADO);
        order.setCancelledAt(Instant.now());
        orderRepo.saveAndFlush(order);

        purchaseService.applySnapshot(new PaymentSnapshot("mp-approve-after-cancel", PaymentStatus.APPROVED,
            "accredited", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));

        entityManager.flush();
        entityManager.clear();

        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.APPROVED);

        // The cancelled order is left exactly as it was — never reopened.
        Order untouched = orderRepo.findById(order.getId()).orElseThrow();
        assertThat(untouched.getEstado()).isEqualTo(OrderEstado.CANCELADO);

        // The lunches the customer paid for don't get lost: they land in
        // AVAILABLE (not COMMITTED, which would require reopening the order).
        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(2);
        assertThat(wallet.getCommitted()).isZero();

        List<CreditMovement> movements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getType()).isEqualTo(MovementType.DIRECT_PURCHASE_REFUND);
        assertThat(movements.get(0).getDeltaAvailable()).isEqualTo(2);
        assertThat(movements.get(0).getDeltaCommitted()).isZero();
    }

    // ─── getPurchase(): packNombre (feature b2c-ordering-redesign, task B3) ─
    //
    // The frontend shows "Paquete Semana" etc. instead of a generic label —
    // GET /api/v1/credits/purchases/{id} must include the purchased pack's
    // nombre for PACK purchases, and null for DIRECT.

    @Test
    @DisplayName("getPurchase(): a PACK purchase includes the pack's nombre as packNombre")
    void getPurchaseOfPackIncludesPackNombre() {
        User user = persistUser("get-pack-nombre");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8))
            .nombre("Paquete Semana")
            .creditAmount(20)
            .priceCents(45_000L)
            .discountPercent(10)
            .ordenDisplay(0)
            .enabled(true)
            .build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-get-pack", "https://mp.test/init"));

        CreditPurchaseCheckoutDto checkout = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null));

        CreditPurchaseDto dto = purchaseService.getPurchase(user.getId(), checkout.purchaseId());

        assertThat(dto.packNombre()).isEqualTo("Paquete Semana");
    }

    @Test
    @DisplayName("getPurchase(): a DIRECT purchase has packNombre null")
    void getPurchaseOfDirectHasNullPackNombre() {
        User user = persistUser("get-direct-nombre");
        Dish dish = persistDishWithStock(5);
        Order order = persistOrderAwaitingPayment(user, dish, 2);
        CreditPurchase purchase = persistDirectPurchase(user, order, 2, 3_000L);

        CreditPurchaseDto dto = purchaseService.getPurchase(user.getId(), purchase.getId());

        assertThat(dto.packNombre()).isNull();
    }

    // ─── applyStatusMapping(): APPROVED after an IN_MEDIATION dispute ───────
    // (feature b2c-ordering-redesign, task B4 — real bug found in review)
    //
    // Mercado Pago can send in_mediation for a claim/chargeback dispute and
    // later resolve it back to approved. Two transitions matter here:
    //  (a) PENDING -> IN_MEDIATION -> APPROVED: never credited yet, so MP
    //      resolving the dispute for the buyer must still credit it.
    //  (b) APPROVED (already credited) -> IN_MEDIATION -> APPROVED: must not
    //      credit a second time, just return the status to APPROVED.

    @Test
    @DisplayName("applySnapshot(): a purchase that goes PENDING -> IN_MEDIATION -> APPROVED is credited exactly once")
    void purchaseApprovedAfterMediationFromPendingIsCredited() {
        User user = persistUser("mediation-from-pending");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8)).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-mediation-pending", "https://mp.test/init"));
        CreditPurchaseCheckoutDto checkout = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null));
        CreditPurchase purchase = purchaseRepo.findById(checkout.purchaseId()).orElseThrow();

        purchaseService.applySnapshot(new PaymentSnapshot("mp-mediation-pending", PaymentStatus.IN_MEDIATION,
            "in_mediation", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));
        entityManager.flush();
        entityManager.clear();
        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.IN_MEDIATION);

        purchaseService.applySnapshot(new PaymentSnapshot("mp-mediation-pending", PaymentStatus.APPROVED,
            "accredited", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));
        entityManager.flush();
        entityManager.clear();

        CreditPurchase resolved = purchaseRepo.findById(purchase.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(resolved.getCreditedAt()).isNotNull();

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(20);

        long packPurchaseMovements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(m -> m.getType() == MovementType.PACK_PURCHASE)
            .count();
        assertThat(packPurchaseMovements).isEqualTo(1);
    }

    @Test
    @DisplayName("applySnapshot(): a purchase that goes APPROVED -> IN_MEDIATION -> APPROVED returns to APPROVED without crediting again")
    void purchaseApprovedAfterMediationFromApprovedIsNotCreditedAgain() {
        User user = persistUser("mediation-from-approved");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + java.util.UUID.randomUUID().toString().substring(0, 8)).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-mediation-approved", "https://mp.test/init"));
        CreditPurchaseCheckoutDto checkout = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, null));
        CreditPurchase purchase = purchaseRepo.findById(checkout.purchaseId()).orElseThrow();

        purchaseService.applySnapshot(new PaymentSnapshot("mp-mediation-approved", PaymentStatus.APPROVED,
            "accredited", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));
        entityManager.flush();
        entityManager.clear();
        CreditPurchase credited = purchaseRepo.findById(purchase.getId()).orElseThrow();
        assertThat(credited.getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        Instant firstCreditedAt = credited.getCreditedAt();
        assertThat(firstCreditedAt).isNotNull();

        purchaseService.applySnapshot(new PaymentSnapshot("mp-mediation-approved", PaymentStatus.IN_MEDIATION,
            "in_mediation", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));
        entityManager.flush();
        entityManager.clear();
        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.IN_MEDIATION);

        purchaseService.applySnapshot(new PaymentSnapshot("mp-mediation-approved", PaymentStatus.APPROVED,
            "accredited", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));
        entityManager.flush();
        entityManager.clear();

        CreditPurchase resolved = purchaseRepo.findById(purchase.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(resolved.getCreditedAt()).isEqualTo(firstCreditedAt);

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(20); // not doubled to 40

        long packPurchaseMovements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(m -> m.getType() == MovementType.PACK_PURCHASE)
            .count();
        assertThat(packPurchaseMovements).isEqualTo(1); // no extra movement from the second APPROVED
    }

    // ─── B7.1 (revisión): concurrencia real en creditApprovedPurchase() ────
    //
    // Sin lock, creditApprovedPurchase() leía purchase.getOrder().getEstado()
    // con un SELECT plano. Un cancel() del cliente o el corte del scheduler
    // pueden cancelar el MISMO pedido en paralelo con la aprobación, y esa
    // lectura sin lock podía decidir sobre una foto vieja: pedido CANCELADO
    // con créditos igual comprometidos (nunca liberados), o pedido PENDIENTE
    // con el stock ya restaurado por la cancelación.
    //
    // Fix: la aprobación y closeForPaymentFailure bloquean el pedido
    // (findByIdForUpdate) antes de decidir.
    // Mismo patrón de dos hilos reales que
    // OrderPlacementServiceTest#removeItemConcurrenteSerializaBajoElLock...:
    // NOT_SUPPORTED suspende la transacción de test para que cada llamada
    // abra su propia transacción física, visible entre conexiones.
    //
    // Sin el lock, la carrera se reprodujo en 6 de 6 corridas (ambos casos);
    // con el lock el resultado siempre cae en una de las dos ramas válidas.

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("creditApprovedPurchase(): aprobación concurrente con cancel() del cliente nunca deja CANCELADO con créditos comprometidos ni PENDIENTE con stock restaurado")
    void approvalConcurrenteConCancelDelClienteRespetaElInvariante() throws Exception {
        assertApprovalRaceAgainst("race-approve-cancel", 5, 2,
            (user, order) -> orderPlacementService.cancel(user.getId(), order.getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("creditApprovedPurchase(): aprobación concurrente con el corte del scheduler nunca deja CANCELADO con créditos comprometidos ni PENDIENTE con stock restaurado")
    void approvalConcurrenteConElCorteDelSchedulerRespetaElInvariante() throws Exception {
        // Misma operación que OrderConsumptionScheduler.consumeDueOrders() ejecuta por pedido.
        assertApprovalRaceAgainst("race-approve-scheduler", 3, 1,
            (user, order) -> orderPlacementService.closeForPaymentFailure(order.getId()));
    }

    /** Aprueba el pago DIRECT en paralelo con {@code competitor} y verifica el invariante. */
    private void assertApprovalRaceAgainst(String prefix, int stock, int lunches,
                                           java.util.function.BiConsumer<User, Order> competitor) throws Exception {
        User user = persistUserForRaceTest(prefix);
        Dish dish = persistDishWithStock(stock);
        Order order = new TransactionTemplate(txManager).execute(s -> persistOrderAwaitingPayment(user, dish, lunches));
        CreditPurchase purchase = persistDirectPurchase(user, order, lunches, lunches * 1_000L);
        PaymentSnapshot approved = new PaymentSnapshot("mp-" + prefix, PaymentStatus.APPROVED,
            "accredited", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L);
        try {
            runConcurrently(() -> purchaseService.applySnapshot(approved), () -> competitor.accept(user, order));
            assertOrderApprovalRaceInvariant(order.getId(), user.getId(), dish.getId(), lunches, stock, stock - lunches);
        } finally {
            cleanupRaceTestData(user, order, dish);
        }
    }

    /** Como {@link #persistUser}, pero único entre corridas: estos tests commitean de verdad y {@code PHONE_SEQ} se reinicia por JVM. */
    private User persistUserForRaceTest(String prefix) {
        long nanos = System.nanoTime();
        User user = User.builder()
            .email(prefix + "-" + nanos + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone("+549" + (1_000_000_000L + (nanos % 900_000_000L)))
            .nickname("Apodo-" + nanos)
            .build();
        return userRepo.save(user);
    }

    /** Limpieza manual — necesaria porque estos tests commitean de verdad (ver {@link #persistUserForRaceTest}). */
    private void cleanupRaceTestData(User user, Order order, Dish dish) {
        movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()).forEach(movementRepo::delete);
        walletRepo.findById(user.getId()).ifPresent(walletRepo::delete);
        purchaseRepo.findByOrderIdAndType(order.getId(), PurchaseType.DIRECT).ifPresent(purchaseRepo::delete);
        orderRepo.findById(order.getId()).ifPresent(orderRepo::delete);
        userRepo.delete(user);
        Dish reloaded = dishRepo.findById(dish.getId()).orElseThrow();
        dishRepo.delete(reloaded);
        categoryRepo.delete(reloaded.getCategory());
        menuSectionRepo.delete(reloaded.getMenuSection());
    }

    private void runConcurrently(Runnable... tasks) throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(tasks.length);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Thread> threads = new java.util.ArrayList<>();
        for (Runnable task : tasks) {
            threads.add(new Thread(() -> {
                startLatch.countDown();
                try {
                    startLatch.await();
                    task.run();
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(error.get()).isNull();
    }

    /**
     * Único invariante válido tras la carrera, sin importar qué hilo ganó:
     * o el pedido quedó PENDIENTE con los créditos comprometidos EXACTAMENTE
     * una vez y el stock todavía reservado, o quedó CANCELADO con los
     * créditos reembolsados a AVAILABLE y el stock totalmente restaurado.
     * NUNCA CANCELADO con créditos comprometidos, NUNCA PENDIENTE con el
     * stock ya restaurado.
     */
    private void assertOrderApprovalRaceInvariant(
        Long orderId, Long userId, Long dishId, int creditTotal, int fullStock, int reservedStock) {
        Order finalOrder = orderRepo.findById(orderId).orElseThrow();
        CreditWallet wallet = walletRepo.findById(userId).orElseThrow();
        int stock = dishRepo.findById(dishId).orElseThrow().getStockActual();

        if (finalOrder.getEstado() == OrderEstado.PENDIENTE) {
            assertThat(wallet.getCommitted()).isEqualTo(creditTotal);
            assertThat(wallet.getAvailable()).isZero();
            assertThat(stock).isEqualTo(reservedStock);
        } else {
            assertThat(finalOrder.getEstado()).isEqualTo(OrderEstado.CANCELADO);
            assertThat(wallet.getCommitted()).isZero();
            assertThat(wallet.getAvailable()).isEqualTo(creditTotal);
            assertThat(stock).isEqualTo(fullStock);
        }
    }
}
