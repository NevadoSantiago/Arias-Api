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
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderItem;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private EntityManager entityManager;

    @MockitoBean
    private PaymentGateway paymentGateway;

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
            .code("WEEK-" + System.nanoTime())
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
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null));

        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getCreditAmount()).isEqualTo(20);
        assertThat(purchase.getAmountCents()).isEqualTo(45_000L);
        assertThat(purchase.getStatus()).isEqualTo(CreditPurchaseStatus.PENDING);
        assertThat(purchase.getMpPreferenceId()).isEqualTo("pref-1");
        assertThat(dto.initPoint()).isEqualTo("https://mp.test/init");

        // El importe pasado a Mercado Pago es el de la base, nunca uno inventado por el cliente.
        verify(paymentGateway).createCheckout(argThat(req -> req.unitPriceCents() == 45_000L
            && req.externalReference().equals(purchase.getId().toString())));
    }

    @Test
    void compraDirectaCalculaElImporteDesdeElPaqueteDayNuncaDelCliente() {
        User user = persistUser("direct-amount");
        packRepo.save(CreditPack.builder()
            .code("DAY")
            .nombre("Día")
            .creditAmount(2)
            .priceCents(3_000L) // 1500 centavos por crédito
            .discountPercent(0)
            .ordenDisplay(0)
            .enabled(true)
            .build());

        Order order = orderRepo.save(Order.builder()
            .user(user)
            .fecha(LocalDate.now())
            .pickupAt(Instant.now().plus(2, ChronoUnit.HOURS))
            .estado(OrderEstado.PENDIENTE)
            .creditTotal(4)
            .build());

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-2", "https://mp.test/init"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.DIRECT, null, order.getId()));

        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();
        assertThat(purchase.getCreditAmount()).isEqualTo(4);
        assertThat(purchase.getAmountCents()).isEqualTo(6_000L); // 1500 * 4
    }

    @Test
    void compraDirectaSinPaqueteDaySeRechazaConServicioNoDisponible() {
        User user = persistUser("no-day-pack");
        Order order = orderRepo.save(Order.builder()
            .user(user)
            .fecha(LocalDate.now())
            .pickupAt(Instant.now().plus(2, ChronoUnit.HOURS))
            .estado(OrderEstado.PENDIENTE)
            .creditTotal(2)
            .build());

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.DIRECT, null, order.getId())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "direct-purchase-unavailable");

        assertThat(purchaseRepo.findAll()).isEmpty();
    }

    @Test
    void pedidoQueNoEsDelUsuarioSeRechaza() {
        User owner = persistUser("owner");
        User other = persistUser("other");
        packRepo.save(CreditPack.builder()
            .code("DAY").nombre("Día").creditAmount(1).priceCents(1_000L)
            .discountPercent(0).ordenDisplay(0).enabled(true).build());
        Order order = orderRepo.save(Order.builder()
            .user(owner)
            .fecha(LocalDate.now())
            .pickupAt(Instant.now().plus(2, ChronoUnit.HOURS))
            .estado(OrderEstado.PENDIENTE)
            .creditTotal(1)
            .build());

        assertThatThrownBy(() -> purchaseService.createPurchase(other.getId(),
            new CreatePurchaseRequest(PurchaseType.DIRECT, null, order.getId())))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-not-found");
    }

    // ─── Gate de verificación de correo (gap de la unidad 4/5, no de login) ─

    @Test
    void compraSeRechazaSiElB2cNoVerificoElCorreo() {
        User user = persistUnverifiedUser("sin-verificar");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + System.nanoTime()).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "email-not-verified");

        assertThat(purchaseRepo.findAll()).isEmpty();
        verify(paymentGateway, org.mockito.Mockito.never()).createCheckout(any());
    }

    @Test
    void compraSeAceptaSiElB2cVerificoElCorreo() {
        User user = persistUser("verificado");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + System.nanoTime()).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-verificado", "https://mp.test/init"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null));

        assertThat(dto.purchaseId()).isNotNull();
    }

    @Test
    void compraNoSeBloqueaParaEmpleadoDeEmpresaConEmailVerifiedAtNulo() {
        User employee = persistCompanyEmployeeWithNullEmailVerifiedAt();
        assertThat(employee.getEmailVerifiedAt()).isNull(); // exactamente el caso que V16 no pudo rellenar
        assertThat(employee.getPhone()).isNull(); // el alta por lista blanca nunca captura teléfono ni apodo
        assertThat(employee.getNickname()).isNull();
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + System.nanoTime()).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-empleado", "https://mp.test/init"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(employee.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null));

        assertThat(dto.purchaseId()).isNotNull();
    }

    // ─── Gate de perfil incompleto (gap de la unidad 9, diseño §Decisión 9) ─

    @Test
    void compraSeRechazaSiElB2cNoTieneTelefono() {
        User user = persistUserConPerfilIncompleto("sin-telefono", null, "Apodo-" + System.nanoTime());
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + System.nanoTime()).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null)))
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
            .code("WEEK-" + System.nanoTime()).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "profile-incomplete");
    }

    @Test
    void compraSeAceptaSiElB2cTienePerfilCompleto() {
        User user = persistUser("perfil-completo");
        assertThat(user.getPhone()).isNotBlank();
        assertThat(user.getNickname()).isNotBlank();
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + System.nanoTime()).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-perfil-completo", "https://mp.test/init"));

        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null));

        assertThat(dto.purchaseId()).isNotNull();
    }

    @Test
    void compraConAmbosGatesDisparablesMuestraEmailNotVerifiedPrimero() {
        // Sin verificar Y sin teléfono/apodo — ambos gates aplicarían.
        User user = persistUnverifiedUser("ambos-gates");
        assertThat(user.getPhone()).isNull();
        assertThat(user.getNickname()).isNull();
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + System.nanoTime()).nombre("Semana").creditAmount(20)
            .priceCents(45_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());

        assertThatThrownBy(() -> purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "email-not-verified");
    }

    private static CheckoutRequest argThat(java.util.function.Predicate<CheckoutRequest> predicate) {
        return org.mockito.Mockito.argThat(predicate::test);
    }

    // ─── Gap fix: a rejected/cancelled/expired DIRECT purchase closes its ───
    // ─── order (design.md §Flujo de datos, "rejected/cancelled" row) ────────
    //
    // Identifiers/DisplayNames in English per this gap fix's explicit
    // instruction, unlike the Spanish identifiers above from the original
    // unit-11 batch.
    //
    // The order/dish/wallet state below is built directly instead of going
    // through OrderPlacementService.place() (which requires a valid pickup
    // time inside the restaurant_config service window against the real
    // system clock) — it reproduces exactly the state place() leaves behind
    // for a successful order (stock already decremented, wallet already
    // committed), which is all this closing logic depends on. Pickup-window
    // validation itself is already covered by OrderPlacementServiceTest.

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
     * OrderPlacementService.place()} would leave it: stock already
     * decremented for its single item, and the user's wallet already
     * COMMITTED for {@code creditTotal} (AVAILABLE 0).
     */
    private Order persistOrderWithCommittedCredits(User user, Dish dish, int creditTotal) {
        dishRepo.decrementStock(dish.getId());

        Order order = Order.builder()
            .user(user)
            .fecha(LocalDate.now())
            .pickupAt(Instant.now().plus(2, ChronoUnit.HOURS))
            .estado(OrderEstado.PENDIENTE)
            .creditTotal(creditTotal)
            .build();
        order.addItem(OrderItem.builder()
            .dish(dish)
            .dishNombre(dish.getNombre())
            .dishCategoria(dish.getCategory().getNombre())
            .creditCost(creditTotal)
            .build());
        order = orderRepo.save(order);

        walletRepo.saveAndFlush(CreditWallet.builder()
            .userId(user.getId())
            .available(0)
            .committed(creditTotal)
            .build());

        return order;
    }

    private CreditPack persistDayPackForGapFix(int creditAmount, long priceCents) {
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

    @Test
    @DisplayName("applySnapshot(): a rejected DIRECT purchase leaves its order CANCELADO, with stock restored and credits released")
    void rejectedDirectPurchaseClosesItsOrder() {
        User user = persistUser("reject-direct");
        persistDayPackForGapFix(2, 2_000L); // 1000 centavos por crédito
        Dish dish = persistDishWithStock(5);
        Order order = persistOrderWithCommittedCredits(user, dish, 2);

        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(4);

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-reject", "https://mp.test/init"));
        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.DIRECT, null, order.getId()));
        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();

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

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(2);
        assertThat(wallet.getCommitted()).isZero();
    }

    @Test
    @DisplayName("applySnapshot(): processing the same rejection twice changes nothing the second time")
    void repeatedRejectionIsIdempotent() {
        User user = persistUser("reject-direct-dup");
        persistDayPackForGapFix(2, 2_000L);
        Dish dish = persistDishWithStock(5);
        Order order = persistOrderWithCommittedCredits(user, dish, 2);

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-reject-dup", "https://mp.test/init"));
        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.DIRECT, null, order.getId()));
        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();

        PaymentSnapshot rejected = new PaymentSnapshot("mp-reject-dup", PaymentStatus.REJECTED,
            "cc_rejected_other_reason", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L);

        purchaseService.applySnapshot(rejected);
        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(5);
        CreditWallet walletAfterFirst = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(walletAfterFirst.getAvailable()).isEqualTo(2);
        assertThat(walletAfterFirst.getCommitted()).isZero();

        // Same webhook notification delivered a second time (Mercado Pago
        // retries, or the reconciliation job re-processes it).
        purchaseService.applySnapshot(rejected);
        entityManager.flush();
        entityManager.clear();

        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(5);
        CreditWallet walletAfterSecond = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(walletAfterSecond.getAvailable()).isEqualTo(2);
        assertThat(walletAfterSecond.getCommitted()).isZero();
        assertThat(orderRepo.findById(order.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.CANCELADO);
    }

    @Test
    @DisplayName("expirePendingPurchase(): an expired DIRECT purchase closes its order the same way a rejection does")
    void expiredDirectPurchaseClosesItsOrder() {
        User user = persistUser("expire-direct");
        persistDayPackForGapFix(3, 3_000L);
        Dish dish = persistDishWithStock(4);
        Order order = persistOrderWithCommittedCredits(user, dish, 3);

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-expire", "https://mp.test/init"));
        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.DIRECT, null, order.getId()));
        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();

        // Reached via PaymentReconciliationScheduler after 24h without a
        // reported payment — never through the webhook.
        purchaseService.expirePendingPurchase(purchase.getId());

        entityManager.flush();
        entityManager.clear();

        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.EXPIRED);
        assertThat(orderRepo.findById(order.getId()).orElseThrow().getEstado()).isEqualTo(OrderEstado.CANCELADO);
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(4);

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(3);
        assertThat(wallet.getCommitted()).isZero();
    }

    @Test
    @DisplayName("applySnapshot(): a rejected PACK purchase touches no order")
    void rejectedPackPurchaseTouchesNoOrder() {
        User user = persistUser("reject-pack");
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("WEEK-" + System.nanoTime()).nombre("Semana").creditAmount(10)
            .priceCents(10_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-reject-pack", "https://mp.test/init"));
        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null));
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
    @DisplayName("applySnapshot(): a DIRECT purchase that is approved leaves its order alone")
    void approvedDirectPurchaseLeavesItsOrderAlone() {
        User user = persistUser("approve-direct");
        persistDayPackForGapFix(2, 2_000L);
        Dish dish = persistDishWithStock(5);
        Order order = persistOrderWithCommittedCredits(user, dish, 2);

        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-approve", "https://mp.test/init"));
        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.DIRECT, null, order.getId()));
        CreditPurchase purchase = purchaseRepo.findById(dto.purchaseId()).orElseThrow();

        purchaseService.applySnapshot(new PaymentSnapshot("mp-approve-1", PaymentStatus.APPROVED,
            "accredited", purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L));

        entityManager.flush();
        entityManager.clear();

        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.APPROVED);

        Order untouched = orderRepo.findById(order.getId()).orElseThrow();
        assertThat(untouched.getEstado()).isEqualTo(OrderEstado.PENDIENTE);
        assertThat(untouched.getCancelledAt()).isNull();

        // Stock stays as it was left by the (simulated) original placement —
        // the approved DIRECT_PURCHASE never touches stock, only the ledger.
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(4);

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        // Committed already had 2 from placement; DIRECT_PURCHASE adds 2 more
        // committed on top (deltaAvailable=0, deltaCommitted=+creditAmount).
        assertThat(wallet.getCommitted()).isEqualTo(4);
        assertThat(wallet.getAvailable()).isZero();
    }
}
