package com.arias.payments;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.common.exception.BusinessException;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
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
}
