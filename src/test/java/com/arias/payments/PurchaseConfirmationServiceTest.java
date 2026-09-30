package com.arias.payments;

import com.arias.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirmation on return from Mercado Pago: the gateway lookup is gated by
 * ownership, status and a per-purchase throttle, and its failures never break
 * the request. Crediting itself is covered in {@code PurchaseConfirmationCreditTest}.
 */
class PurchaseConfirmationServiceTest {

    private static final Long USER_ID = 7L;
    private static final Instant T0 = Instant.parse("2026-09-30T12:00:00Z");

    private final CreditPurchaseService purchaseService = mock(CreditPurchaseService.class);
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private PurchaseConfirmationService confirmation;
    private UUID purchaseId;

    @BeforeEach
    void setUp() {
        confirmation = new PurchaseConfirmationService(
            purchaseService, paymentGateway, new PurchaseConfirmThrottle(clock));
        purchaseId = UUID.randomUUID();
    }

    private CreditPurchaseDto dto(CreditPurchaseStatus status) {
        CreditPurchaseDto dto = mock(CreditPurchaseDto.class);
        when(dto.status()).thenReturn(status);
        return dto;
    }

    private PaymentSnapshot snapshot(PaymentStatus status) {
        return new PaymentSnapshot("mp-1", status, "detail", 1_000L, "ARS", purchaseId.toString(), 0L);
    }

    @Test
    void foreignOrUnknownPurchaseIs404AndNeverCallsTheGateway() {
        when(purchaseService.getPurchase(USER_ID, purchaseId))
            .thenThrow(BusinessException.notFound("purchase-not-found", "Compra no encontrada"));

        assertThatThrownBy(() -> confirmation.confirm(USER_ID, purchaseId))
            .isInstanceOf(BusinessException.class);

        verify(paymentGateway, never()).findRecentByExternalReference(any());
        verify(purchaseService, never()).applySnapshot(any());
    }

    @Test
    void nonPendingPurchaseIsReturnedWithoutCallingTheGateway() {
        CreditPurchaseDto approved = dto(CreditPurchaseStatus.APPROVED);
        when(purchaseService.getPurchase(USER_ID, purchaseId)).thenReturn(approved);

        assertThat(confirmation.confirm(USER_ID, purchaseId)).isSameAs(approved);

        verify(paymentGateway, never()).findRecentByExternalReference(any());
    }

    @Test
    void pendingPurchaseWithApprovedSnapshotIsApplied() {
        CreditPurchaseDto pending = dto(CreditPurchaseStatus.PENDING);
        CreditPurchaseDto approved = dto(CreditPurchaseStatus.APPROVED);
        when(purchaseService.getPurchase(USER_ID, purchaseId)).thenReturn(pending, approved);
        PaymentSnapshot rejected = snapshot(PaymentStatus.REJECTED);
        PaymentSnapshot ok = snapshot(PaymentStatus.APPROVED);
        when(paymentGateway.findRecentByExternalReference(purchaseId.toString()))
            .thenReturn(List.of(rejected, ok));

        assertThat(confirmation.confirm(USER_ID, purchaseId)).isSameAs(approved);

        verify(purchaseService).applySnapshot(ok);
    }

    @Test
    void noSnapshotYetReturnsCurrentStateWithoutApplying() {
        CreditPurchaseDto pending = dto(CreditPurchaseStatus.PENDING);
        when(purchaseService.getPurchase(USER_ID, purchaseId)).thenReturn(pending);
        when(paymentGateway.findRecentByExternalReference(purchaseId.toString())).thenReturn(List.of());

        assertThat(confirmation.confirm(USER_ID, purchaseId)).isSameAs(pending);

        verify(purchaseService, never()).applySnapshot(any());
    }

    @Test
    void twoConfirmsWithinFiveSecondsQueryTheGatewayOnce() {
        CreditPurchaseDto pending = dto(CreditPurchaseStatus.PENDING);
        when(purchaseService.getPurchase(USER_ID, purchaseId)).thenReturn(pending);
        when(paymentGateway.findRecentByExternalReference(purchaseId.toString())).thenReturn(List.of());

        confirmation.confirm(USER_ID, purchaseId);
        now.set(T0.plusSeconds(4));
        confirmation.confirm(USER_ID, purchaseId);

        verify(paymentGateway, times(1)).findRecentByExternalReference(purchaseId.toString());

        now.set(T0.plus(Duration.ofSeconds(5)));
        confirmation.confirm(USER_ID, purchaseId);

        verify(paymentGateway, times(2)).findRecentByExternalReference(purchaseId.toString());
    }

    @Test
    void gatewayFailureStillReturnsTheCurrentPendingState() {
        CreditPurchaseDto pending = dto(CreditPurchaseStatus.PENDING);
        when(purchaseService.getPurchase(USER_ID, purchaseId)).thenReturn(pending);
        when(paymentGateway.findRecentByExternalReference(purchaseId.toString()))
            .thenThrow(new IllegalStateException("MP down"));

        assertThat(confirmation.confirm(USER_ID, purchaseId)).isSameAs(pending);

        verify(purchaseService, never()).applySnapshot(any());
    }

    @Test
    void applyFailureStillReturnsTheCurrentState() {
        CreditPurchaseDto pending = dto(CreditPurchaseStatus.PENDING);
        when(purchaseService.getPurchase(USER_ID, purchaseId)).thenReturn(pending);
        PaymentSnapshot ok = snapshot(PaymentStatus.APPROVED);
        when(paymentGateway.findRecentByExternalReference(purchaseId.toString())).thenReturn(List.of(ok));
        doThrow(new IllegalStateException("boom")).when(purchaseService).applySnapshot(ok);

        assertThat(confirmation.confirm(USER_ID, purchaseId)).isSameAs(pending);
    }

    @Test
    void throttleEvictsStaleEntries() {
        PurchaseConfirmThrottle throttle = new PurchaseConfirmThrottle(clock);
        assertThat(throttle.tryAcquire(UUID.randomUUID())).isTrue();
        now.set(T0.plus(Duration.ofMinutes(11)));
        assertThat(throttle.tryAcquire(UUID.randomUUID())).isTrue();
        assertThat(throttle.size()).isEqualTo(1);
    }
}
