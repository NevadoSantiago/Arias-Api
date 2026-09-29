package com.arias.payments;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.arias.payments.mercadopago.MercadoPagoProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One purchase failing must not stop the hourly reconciliation for the rest.
 * Found against the real Mercado Pago sandbox: an unexpected SDK exception on
 * one purchase aborted the whole run, and it would do so again every hour.
 */
class PaymentReconciliationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-24T15:00:00Z");
    private static final int CLOSED_MAX_PER_RUN = 3;

    private final CreditPurchaseRepository purchaseRepo = mock(CreditPurchaseRepository.class);
    private final CreditPurchaseService purchaseService = mock(CreditPurchaseService.class);
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private Logger schedulerLogger;
    private PaymentReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PaymentReconciliationScheduler(
            purchaseRepo,
            purchaseService,
            paymentGateway,
            new MercadoPagoProperties("token", "secret", true, 5_000, 5_000, 10_000),
            Clock.fixed(NOW, ZoneOffset.UTC),
            CLOSED_MAX_PER_RUN);
        schedulerLogger = (Logger) LoggerFactory.getLogger(PaymentReconciliationScheduler.class);
        logs.start();
        schedulerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        schedulerLogger.detachAppender(logs);
    }

    @Test
    void lookupFailureOnOnePurchaseStillReconcilesTheNext() {
        CreditPurchase failing = pendingCreatedMinutesAgo(40);
        CreditPurchase healthy = pendingCreatedMinutesAgo(45);
        when(purchaseRepo.findByStatusAndCreatedAtBefore(eq(CreditPurchaseStatus.PENDING), any()))
            .thenReturn(List.of(failing, healthy));
        when(paymentGateway.findAllByExternalReference(failing.getId().toString()))
            .thenThrow(new NullPointerException("unexpected SDK failure"));
        PaymentSnapshot approved = approvedSnapshotFor(healthy);
        when(paymentGateway.findAllByExternalReference(healthy.getId().toString()))
            .thenReturn(List.of(approved));

        scheduler.reconcile();

        verify(purchaseService).applySnapshot(approved);
    }

    @Test
    void expiryFailureOnOnePurchaseStillExpiresTheNext() {
        CreditPurchase failing = pendingCreatedMinutesAgo(25 * 60);
        CreditPurchase healthy = pendingCreatedMinutesAgo(26 * 60);
        when(purchaseRepo.findByStatusAndCreatedAtBefore(eq(CreditPurchaseStatus.PENDING), any()))
            .thenReturn(List.of(failing, healthy));
        doThrow(new IllegalStateException("unexpected failure"))
            .when(purchaseService).expirePendingPurchase(failing.getId());

        scheduler.reconcile();

        verify(purchaseService).expirePendingPurchase(healthy.getId());
    }

    // ─── B15: compras cerradas sin acreditar (reintento con otra tarjeta / pago tardío) ──

    @Test
    void closedUncreditedPurchasesCreatedInTheLast48HoursAreReconciledToo() {
        CreditPurchase rejected = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, 6 * 60 + 10);
        givenClosed(rejected);
        PaymentSnapshot retryApproved = approvedSnapshotFor(rejected);
        when(paymentGateway.findAllByExternalReference(rejected.getId().toString()))
            .thenReturn(List.of(retryApproved));

        scheduler.reconcile();

        verify(purchaseService).applySnapshot(retryApproved);
        verify(purchaseRepo).findByStatusInAndCreditedAtIsNullAndCreatedAtAfter(
            java.util.Set.of(CreditPurchaseStatus.REJECTED, CreditPurchaseStatus.CANCELLED,
                CreditPurchaseStatus.EXPIRED),
            NOW.minus(48, ChronoUnit.HOURS));
    }

    @Test
    void lookupFailureOnAClosedPurchaseStillReconcilesTheNext() {
        CreditPurchase failing = closedCreatedMinutesAgo(CreditPurchaseStatus.EXPIRED, 24 * 60 + 5);
        CreditPurchase healthy = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, 65);
        givenClosed(failing, healthy);
        when(paymentGateway.findAllByExternalReference(failing.getId().toString()))
            .thenThrow(new IllegalStateException("MP down"));
        PaymentSnapshot approved = approvedSnapshotFor(healthy);
        when(paymentGateway.findAllByExternalReference(healthy.getId().toString()))
            .thenReturn(List.of(approved));

        scheduler.reconcile();

        verify(purchaseService).applySnapshot(approved);
    }

    // ─── B15.1: acotar las consultas de la pasada de compras cerradas ───────────

    @ParameterizedTest(name = "compra cerrada de {0} min: se consulta en su punto de control")
    @ValueSource(longs = {60 + 10, 6 * 60 + 10, 24 * 60 + 10, 47 * 60 + 10})
    void closedPurchaseIsQueriedAtEachCheckpoint(long ageMinutes) {
        CreditPurchase rejected = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, ageMinutes);
        givenClosed(rejected);
        when(paymentGateway.findAllByExternalReference(rejected.getId().toString())).thenReturn(List.of());

        scheduler.reconcile();

        verify(paymentGateway).findAllByExternalReference(rejected.getId().toString());
    }

    @ParameterizedTest(name = "compra cerrada de {0} min: entre puntos de control no se consulta")
    @ValueSource(longs = {10, 30, 2 * 60, 3 * 60 + 30, 12 * 60, 30 * 60, 47 * 60 + 90})
    void closedPurchaseIsNotQueriedBetweenCheckpoints(long ageMinutes) {
        CreditPurchase rejected = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, ageMinutes);
        givenClosed(rejected);

        scheduler.reconcile();

        verify(paymentGateway, never()).findAllByExternalReference(any());
    }

    @Test
    void closedPassIsCappedPerRunOldestFirstAndReportsTheDeferredOnes() {
        // 5 vencen en la misma corrida; el tope es 3: se consultan las 3 más viejas.
        CreditPurchase p1 = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, 6 * 60 + 5);
        CreditPurchase p2 = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, 24 * 60 + 5);
        CreditPurchase p3 = closedCreatedMinutesAgo(CreditPurchaseStatus.EXPIRED, 47 * 60 + 5);
        CreditPurchase p4 = closedCreatedMinutesAgo(CreditPurchaseStatus.CANCELLED, 6 * 60 + 20);
        CreditPurchase p5 = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, 65);
        givenClosed(p1, p2, p3, p4, p5);
        when(paymentGateway.findAllByExternalReference(any())).thenReturn(List.of());

        scheduler.reconcile();

        verify(paymentGateway).findAllByExternalReference(p3.getId().toString());
        verify(paymentGateway).findAllByExternalReference(p2.getId().toString());
        verify(paymentGateway).findAllByExternalReference(p4.getId().toString());
        verify(paymentGateway, never()).findAllByExternalReference(p1.getId().toString());
        verify(paymentGateway, never()).findAllByExternalReference(p5.getId().toString());
        assertThat(closedSummaryLog()).contains("3 consultada(s)", "2 diferida(s)");
    }

    @Test
    void closedPassSummaryCountsLateCreditsAndFailuresSeparately() {
        CreditPurchase late = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, 6 * 60 + 5);
        CreditPurchase failing = closedCreatedMinutesAgo(CreditPurchaseStatus.EXPIRED, 24 * 60 + 5);
        givenClosed(late, failing);
        when(paymentGateway.findAllByExternalReference(late.getId().toString()))
            .thenReturn(List.of(approvedSnapshotFor(late)));
        when(paymentGateway.findAllByExternalReference(failing.getId().toString()))
            .thenThrow(new IllegalStateException("MP down"));

        scheduler.reconcile();

        assertThat(closedSummaryLog())
            .contains("2 consultada(s)", "1 con pago aprobado", "1 fallida(s)", "0 diferida(s)");
    }

    // ─── B15.1: varios pagos por external_reference, se prefiere el aprobado ────

    @ParameterizedTest(name = "pagos rechazado + aprobado, aprobado primero = {0}: se aplica el aprobado")
    @ValueSource(booleans = {true, false})
    void approvedPaymentIsPreferredWhateverTheOrder(boolean approvedFirst) {
        CreditPurchase rejected = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, 6 * 60 + 5);
        givenClosed(rejected);
        PaymentSnapshot declined = snapshotFor(rejected, "pay-declined", PaymentStatus.REJECTED);
        PaymentSnapshot approved = snapshotFor(rejected, "pay-approved", PaymentStatus.APPROVED);
        when(paymentGateway.findAllByExternalReference(rejected.getId().toString()))
            .thenReturn(approvedFirst ? List.of(approved, declined) : List.of(declined, approved));

        scheduler.reconcile();

        verify(purchaseService).applySnapshot(approved);
        verify(purchaseService, never()).applySnapshot(declined);
    }

    @Test
    void pendingPurchaseWithARejectedThenApprovedRetryAppliesTheApprovedOne() {
        CreditPurchase pending = pendingCreatedMinutesAgo(40);
        when(purchaseRepo.findByStatusAndCreatedAtBefore(eq(CreditPurchaseStatus.PENDING), any()))
            .thenReturn(List.of(pending));
        PaymentSnapshot declined = snapshotFor(pending, "pay-declined", PaymentStatus.REJECTED);
        PaymentSnapshot approved = snapshotFor(pending, "pay-approved", PaymentStatus.APPROVED);
        when(paymentGateway.findAllByExternalReference(pending.getId().toString()))
            .thenReturn(List.of(declined, approved));

        scheduler.reconcile();

        verify(purchaseService).applySnapshot(approved);
        verify(purchaseService, never()).applySnapshot(declined);
    }

    @Test
    void withoutAnApprovedPaymentTheLatestOneIsApplied() {
        CreditPurchase pending = pendingCreatedMinutesAgo(40);
        when(purchaseRepo.findByStatusAndCreatedAtBefore(eq(CreditPurchaseStatus.PENDING), any()))
            .thenReturn(List.of(pending));
        PaymentSnapshot latest = snapshotFor(pending, "pay-latest", PaymentStatus.IN_PROCESS);
        PaymentSnapshot older = snapshotFor(pending, "pay-older", PaymentStatus.REJECTED);
        when(paymentGateway.findAllByExternalReference(pending.getId().toString()))
            .thenReturn(List.of(latest, older));

        scheduler.reconcile();

        verify(purchaseService).applySnapshot(latest);
        verify(purchaseService, never()).applySnapshot(older);
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private void givenClosed(CreditPurchase... purchases) {
        when(purchaseRepo.findByStatusAndCreatedAtBefore(eq(CreditPurchaseStatus.PENDING), any()))
            .thenReturn(List.of());
        when(purchaseRepo.findByStatusInAndCreditedAtIsNullAndCreatedAtAfter(any(), any()))
            .thenReturn(List.of(purchases));
    }

    private String closedSummaryLog() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains("compras cerradas")).findFirst().orElse("");
    }

    private CreditPurchase closedCreatedMinutesAgo(CreditPurchaseStatus status, long minutes) {
        return CreditPurchase.builder()
            .status(status)
            .createdAt(NOW.minus(minutes, ChronoUnit.MINUTES))
            .build();
    }

    private CreditPurchase pendingCreatedMinutesAgo(long minutes) {
        return CreditPurchase.builder()
            .status(CreditPurchaseStatus.PENDING)
            .createdAt(NOW.minus(minutes, ChronoUnit.MINUTES))
            .build();
    }

    private PaymentSnapshot approvedSnapshotFor(CreditPurchase purchase) {
        return snapshotFor(purchase, "180649982054", PaymentStatus.APPROVED);
    }

    private PaymentSnapshot snapshotFor(CreditPurchase purchase, String paymentId, PaymentStatus status) {
        return new PaymentSnapshot(paymentId, status, "accredited",
            100_000L, "ARS", purchase.getId().toString(), 0L);
    }
}
