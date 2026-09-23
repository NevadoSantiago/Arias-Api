package com.arias.payments;

import com.arias.credits.CreditMovement;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.MovementType;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Webhook de Mercado Pago end-to-end (unidad 11, tarea 11.7) — cubre spec
 * {@code credit-pack-purchase} completa: duplicado no acredita dos veces
 * contra la restricción real, firma inválida rechaza sin acreditar, la
 * redirección sola no acredita, compra directa = un único movimiento,
 * importe que no coincide rechaza, y reembolso parcial revierte
 * proporcionalmente (decisión del orquestador que resuelve la unidad 9).
 *
 * <p>{@link PaymentGateway} queda enteramente mockeado (harness de la tabla
 * de unidades de trabajo) — ninguna llamada real a Mercado Pago. El
 * controller se invoca directamente (no HTTP real): el binding de headers/
 * body de Spring no es lo que está bajo prueba, la lógica de negocio sí.
 */
@SpringBootTest
@Transactional
class MercadoPagoWebhookControllerTest {

    @Autowired
    private MercadoPagoWebhookController webhookController;

    @Autowired
    private CreditPurchaseService purchaseService;

    @Autowired
    private CreditPurchaseRepository purchaseRepo;

    @Autowired
    private CreditWalletRepository walletRepo;

    @Autowired
    private CreditMovementRepository movementRepo;

    @Autowired
    private CreditPackRepository packRepo;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private UserRepository userRepo;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private static final String VALID_SIG = "ts=1,v1=deadbeef";
    private static final String REQ_ID = "req-1";
    // System.nanoTime() no alcanza para distinguir dos persistUser() seguidos
    // dentro del mismo test (colisiona en uq_users_phone) — un contador
    // monotónico sí garantiza unicidad.
    private static final AtomicLong PHONE_SEQ = new AtomicLong();

    private User persistUser(String prefix) {
        User user = User.builder()
            .email(prefix + "-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now()) // gate de email-not-verified: no es lo que testea esta suite
            .phone("+549" + (1144550100L + PHONE_SEQ.incrementAndGet())) // gate de profile-incomplete: idem
            .nickname("Apodo-" + System.nanoTime())
            .build();
        return userRepo.save(user);
    }

    private CreditPack persistPack(String code, int creditAmount, long priceCents) {
        return packRepo.save(CreditPack.builder()
            .code(code + "-" + System.nanoTime())
            .nombre("Paquete de prueba")
            .creditAmount(creditAmount)
            .priceCents(priceCents)
            .discountPercent(0)
            .ordenDisplay(0)
            .enabled(true)
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

    private Order persistOrder(User user, int creditTotal) {
        Order order = Order.builder()
            .user(user)
            .fecha(LocalDate.now())
            .pickupAt(Instant.now().plus(2, ChronoUnit.HOURS))
            .estado(OrderEstado.PENDIENTE)
            .creditTotal(creditTotal)
            .build();
        return orderRepo.save(order);
    }

    private CreditPurchase createPackPurchase(User user, CreditPack pack) {
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-" + System.nanoTime(), "https://mp.test/init"));
        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null));
        return purchaseRepo.findById(dto.purchaseId()).orElseThrow();
    }

    private CreditPurchase createDirectPurchase(User user, Order order) {
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-" + System.nanoTime(), "https://mp.test/init"));
        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.DIRECT, null, order.getId()));
        return purchaseRepo.findById(dto.purchaseId()).orElseThrow();
    }

    private PaymentSnapshot approvedSnapshot(CreditPurchase purchase, String paymentId) {
        return new PaymentSnapshot(paymentId, PaymentStatus.APPROVED, "accredited",
            purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L);
    }

    private MercadoPagoWebhookController.WebhookPayload paymentPayload(String paymentId) {
        return new MercadoPagoWebhookController.WebhookPayload(
            "payment", "payment.updated", new MercadoPagoWebhookController.WebhookPayload.Data(paymentId));
    }

    // ─── Firma inválida ─────────────────────────────────────────────────────

    @Test
    void firmaInvalidaRechazaSinAcreditar() {
        User user = persistUser("invalid-sig");
        CreditPack pack = persistPack("PACK", 10, 10_000L);
        CreditPurchase purchase = createPackPurchase(user, pack);

        when(paymentGateway.verifySignature(anyString(), anyString(), anyString())).thenReturn(false);

        ResponseEntity<Void> response = webhookController.receive(
            "bad-signature", REQ_ID, paymentPayload("mp-payment-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.PENDING);
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    // ─── Redirección sola (sin webhook) ─────────────────────────────────────

    @Test
    void redireccionSolaNoAcredita() {
        User user = persistUser("redirect-only");
        CreditPack pack = persistPack("PACK", 10, 10_000L);
        CreditPurchase purchase = createPackPurchase(user, pack);

        // Nunca se invoca el webhook — solo la creación de la compra (equivalente
        // a que el usuario vuelva por back_urls sin que Mercado Pago notifique).
        assertThat(purchase.getStatus()).isEqualTo(CreditPurchaseStatus.PENDING);
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
        CreditWallet wallet = walletRepo.findById(user.getId()).orElseGet(() -> CreditWallet.emptyFor(user.getId()));
        assertThat(wallet.getAvailable()).isEqualTo(0);
    }

    // ─── Webhook duplicado ──────────────────────────────────────────────────

    @Test
    void webhookDuplicadoNoAcreditaDosVeces() {
        User user = persistUser("duplicate");
        CreditPack pack = persistPack("PACK", 10, 10_000L);
        CreditPurchase purchase = createPackPurchase(user, pack);

        String paymentId = "mp-payment-dup";
        when(paymentGateway.verifySignature(anyString(), anyString(), anyString())).thenReturn(true);
        when(paymentGateway.getPayment(paymentId)).thenReturn(approvedSnapshot(purchase, paymentId));

        ResponseEntity<Void> first = webhookController.receive(VALID_SIG, REQ_ID, paymentPayload(paymentId));
        ResponseEntity<Void> second = webhookController.receive(VALID_SIG, REQ_ID, paymentPayload(paymentId));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<CreditMovement> movements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getType()).isEqualTo(MovementType.PACK_PURCHASE);

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(10);

        CreditPurchase reloaded = purchaseRepo.findById(purchase.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(reloaded.getMpPaymentId()).isEqualTo(paymentId);
    }

    // ─── Compra directa ─────────────────────────────────────────────────────

    @Test
    void compraDirectaProduceUnUnicoMovimiento() {
        User user = persistUser("direct");
        persistDayPack(10, 10_000L); // 1000 centavos por crédito
        Order order = persistOrder(user, 5);
        CreditPurchase purchase = createDirectPurchase(user, order);
        assertThat(purchase.getAmountCents()).isEqualTo(5_000L);

        String paymentId = "mp-payment-direct";
        when(paymentGateway.verifySignature(anyString(), anyString(), anyString())).thenReturn(true);
        when(paymentGateway.getPayment(paymentId)).thenReturn(approvedSnapshot(purchase, paymentId));

        webhookController.receive(VALID_SIG, REQ_ID, paymentPayload(paymentId));

        List<CreditMovement> movements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getType()).isEqualTo(MovementType.DIRECT_PURCHASE);
        assertThat(movements.get(0).getDeltaAvailable()).isEqualTo(0);
        assertThat(movements.get(0).getDeltaCommitted()).isEqualTo(5);

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(0);
        assertThat(wallet.getCommitted()).isEqualTo(5);
    }

    // ─── Importe no coincide ────────────────────────────────────────────────

    @Test
    void importeQueNoCoincideRechazaSinAcreditar() {
        User user = persistUser("mismatch");
        CreditPack pack = persistPack("PACK", 10, 10_000L);
        CreditPurchase purchase = createPackPurchase(user, pack);

        String paymentId = "mp-payment-mismatch";
        when(paymentGateway.verifySignature(anyString(), anyString(), anyString())).thenReturn(true);
        when(paymentGateway.getPayment(paymentId)).thenReturn(new PaymentSnapshot(
            paymentId, PaymentStatus.APPROVED, "accredited",
            purchase.getAmountCents() - 1, "ARS", purchase.getId().toString(), 0L));

        webhookController.receive(VALID_SIG, REQ_ID, paymentPayload(paymentId));

        assertThat(purchaseRepo.findById(purchase.getId()).orElseThrow().getStatus())
            .isEqualTo(CreditPurchaseStatus.PENDING);
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    // ─── Reembolso parcial ──────────────────────────────────────────────────

    @Test
    void reembolsoParcialRevierteProporcionalmente() {
        User user = persistUser("partial-refund");
        CreditPack pack = persistPack("PACK", 10, 10_000L); // 10 créditos, 10000 centavos
        CreditPurchase purchase = createPackPurchase(user, pack);

        String paymentId = "mp-payment-refund";
        when(paymentGateway.verifySignature(anyString(), anyString(), anyString())).thenReturn(true);

        // 1) aprobado — acredita 10 créditos.
        when(paymentGateway.getPayment(paymentId)).thenReturn(approvedSnapshot(purchase, paymentId));
        webhookController.receive(VALID_SIG, REQ_ID, paymentPayload(paymentId));

        CreditWallet walletAfterCredit = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(walletAfterCredit.getAvailable()).isEqualTo(10);

        // 2) reembolso parcial del 50% — queda approved con transaction_amount_refunded
        // poblado (decisión del orquestador, unidad 9): debe revertir 5 créditos, no 10.
        when(paymentGateway.getPayment(paymentId)).thenReturn(new PaymentSnapshot(
            paymentId, PaymentStatus.APPROVED, "accredited",
            purchase.getAmountCents(), "ARS", purchase.getId().toString(), 5_000L));
        webhookController.receive(VALID_SIG, REQ_ID, paymentPayload(paymentId));

        List<CreditMovement> movements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(movements).hasSize(2);
        assertThat(movements.get(0).getType()).isEqualTo(MovementType.PAYMENT_REVERSAL);
        assertThat(movements.get(0).getDeltaAvailable()).isEqualTo(-5);

        CreditWallet walletAfterReversal = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(walletAfterReversal.getAvailable()).isEqualTo(5);

        CreditPurchase reloaded = purchaseRepo.findById(purchase.getId()).orElseThrow();
        assertThat(reloaded.getCreditsReversed()).isEqualTo(5);
        // Reversión parcial: la compra no se marca REVERSED hasta que se revierta el total.
        assertThat(reloaded.getStatus()).isEqualTo(CreditPurchaseStatus.APPROVED);

        // 3) un segundo webhook con el MISMO reembolso acumulado (retry de Mercado
        // Pago) no debe revertir una segunda vez — el delta ya es 0.
        webhookController.receive(VALID_SIG, REQ_ID, paymentPayload(paymentId));
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).hasSize(2);

        // 4) el reembolso avanza al 100% — revierte el delta restante (5 más), nunca
        // el total original de nuevo, y ahora sí queda REVERSED.
        when(paymentGateway.getPayment(paymentId)).thenReturn(new PaymentSnapshot(
            paymentId, PaymentStatus.REFUNDED, "refunded",
            purchase.getAmountCents(), "ARS", purchase.getId().toString(), 10_000L));
        webhookController.receive(VALID_SIG, REQ_ID, paymentPayload(paymentId));

        List<CreditMovement> finalMovements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(finalMovements).hasSize(3);
        assertThat(finalMovements.get(0).getDeltaAvailable()).isEqualTo(-5);

        CreditWallet finalWallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(finalWallet.getAvailable()).isEqualTo(0);

        CreditPurchase fullyReversed = purchaseRepo.findById(purchase.getId()).orElseThrow();
        assertThat(fullyReversed.getCreditsReversed()).isEqualTo(10);
        assertThat(fullyReversed.getStatus()).isEqualTo(CreditPurchaseStatus.REVERSED);
    }
}
