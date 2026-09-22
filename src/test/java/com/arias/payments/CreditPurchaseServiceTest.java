package com.arias.payments;

import com.arias.common.exception.BusinessException;
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

    @MockitoBean
    private PaymentGateway paymentGateway;

    private User persistUser(String prefix) {
        User user = User.builder()
            .email(prefix + "-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
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

    private static CheckoutRequest argThat(java.util.function.Predicate<CheckoutRequest> predicate) {
        return org.mockito.Mockito.argThat(predicate::test);
    }
}
