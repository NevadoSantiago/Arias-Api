package com.arias.payments;

import com.arias.payments.mercadopago.MercadoPagoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One purchase failing must not stop the hourly reconciliation for the rest.
 * Found against the real Mercado Pago sandbox: an unexpected SDK exception on
 * one purchase aborted the whole run, and it would do so again every hour.
 */
class PaymentReconciliationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-24T15:00:00Z");

    private final CreditPurchaseRepository purchaseRepo = mock(CreditPurchaseRepository.class);
    private final CreditPurchaseService purchaseService = mock(CreditPurchaseService.class);
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);

    private PaymentReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PaymentReconciliationScheduler(
            purchaseRepo,
            purchaseService,
            paymentGateway,
            new MercadoPagoProperties("token", "secret", true, 5_000, 5_000, 10_000),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void lookupFailureOnOnePurchaseStillReconcilesTheNext() {
        CreditPurchase failing = pendingCreatedMinutesAgo(40);
        CreditPurchase healthy = pendingCreatedMinutesAgo(45);
        when(purchaseRepo.findByStatusAndCreatedAtBefore(eq(CreditPurchaseStatus.PENDING), any()))
            .thenReturn(List.of(failing, healthy));
        when(paymentGateway.findByExternalReference(failing.getId().toString()))
            .thenThrow(new NullPointerException("unexpected SDK failure"));
        PaymentSnapshot approved = approvedSnapshotFor(healthy);
        when(paymentGateway.findByExternalReference(healthy.getId().toString()))
            .thenReturn(Optional.of(approved));

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
        CreditPurchase rejected = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, 3 * 60);
        when(purchaseRepo.findByStatusAndCreatedAtBefore(eq(CreditPurchaseStatus.PENDING), any()))
            .thenReturn(List.of());
        when(purchaseRepo.findByStatusInAndCreditedAtIsNullAndCreatedAtAfter(any(), any()))
            .thenReturn(List.of(rejected));
        PaymentSnapshot retryApproved = approvedSnapshotFor(rejected);
        when(paymentGateway.findByExternalReference(rejected.getId().toString()))
            .thenReturn(Optional.of(retryApproved));

        scheduler.reconcile();

        verify(purchaseService).applySnapshot(retryApproved);
        verify(purchaseRepo).findByStatusInAndCreditedAtIsNullAndCreatedAtAfter(
            java.util.Set.of(CreditPurchaseStatus.REJECTED, CreditPurchaseStatus.CANCELLED,
                CreditPurchaseStatus.EXPIRED),
            NOW.minus(48, ChronoUnit.HOURS));
    }

    @Test
    void lookupFailureOnAClosedPurchaseStillReconcilesTheNext() {
        CreditPurchase failing = closedCreatedMinutesAgo(CreditPurchaseStatus.EXPIRED, 30 * 60);
        CreditPurchase healthy = closedCreatedMinutesAgo(CreditPurchaseStatus.REJECTED, 60);
        when(purchaseRepo.findByStatusAndCreatedAtBefore(eq(CreditPurchaseStatus.PENDING), any()))
            .thenReturn(List.of());
        when(purchaseRepo.findByStatusInAndCreditedAtIsNullAndCreatedAtAfter(any(), any()))
            .thenReturn(List.of(failing, healthy));
        when(paymentGateway.findByExternalReference(failing.getId().toString()))
            .thenThrow(new IllegalStateException("MP down"));
        PaymentSnapshot approved = approvedSnapshotFor(healthy);
        when(paymentGateway.findByExternalReference(healthy.getId().toString()))
            .thenReturn(Optional.of(approved));

        scheduler.reconcile();

        verify(purchaseService).applySnapshot(approved);
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
        return new PaymentSnapshot("180649982054", PaymentStatus.APPROVED, "accredited",
            100_000L, "ARS", purchase.getId().toString(), 0L);
    }
}
