package com.arias.payments.mercadopago;

import com.arias.payments.PaymentSnapshot;
import com.mercadopago.resources.payment.Payment;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B15.1: one checkout can hold several payment attempts (a declined card and
 * then an approved one). The reconciliation must see all of them, newest
 * first, instead of the single latest one the old lookup returned.
 */
class MercadoPagoAdapterAllPaymentsTest {

    private final MercadoPagoAdapter adapter =
        new MercadoPagoAdapter(new MercadoPagoProperties("token", "secret", true, 0, 0, 0), null, null);

    @Test
    void snapshotsAreReturnedNewestFirstWhateverTheOrderMercadoPagoUsed() {
        Payment oldest = payment(1L, "rejected", "2026-09-24T10:00:00Z");
        Payment newest = payment(3L, "approved", "2026-09-24T12:00:00Z");
        Payment middle = payment(2L, "rejected", "2026-09-24T11:00:00Z");

        List<PaymentSnapshot> snapshots = adapter.snapshotsNewestFirst(List.of(oldest, newest, middle));

        assertThat(snapshots).extracting(PaymentSnapshot::paymentId).containsExactly("3", "2", "1");
    }

    @Test
    void aPaymentWithoutCreationDateGoesLast() {
        Payment undated = payment(9L, "approved", null);
        Payment dated = payment(2L, "rejected", "2026-09-24T11:00:00Z");

        List<PaymentSnapshot> snapshots = adapter.snapshotsNewestFirst(List.of(undated, dated));

        assertThat(snapshots).extracting(PaymentSnapshot::paymentId).containsExactly("2", "9");
    }

    @Test
    void anEmptyOrNullResultIsAnEmptyList() {
        assertThat(adapter.snapshotsNewestFirst(List.of())).isEmpty();
        assertThat(adapter.snapshotsNewestFirst(null)).isEmpty();
    }

    private static Payment payment(long id, String status, String createdAt) {
        Payment payment = new Payment();
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "status", status);
        ReflectionTestUtils.setField(payment, "externalReference", "ref");
        if (createdAt != null) {
            ReflectionTestUtils.setField(payment, "dateCreated", OffsetDateTime.parse(createdAt));
        }
        return payment;
    }
}
