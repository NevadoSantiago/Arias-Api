package com.arias.payments.mercadopago;

import com.arias.payments.PaymentSnapshot;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.payment.PaymentFeeDetail;
import com.mercadopago.resources.payment.PaymentTransactionDetails;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real Mercado Pago commission is what the admin report nets out of each
 * payment: only the fees the collector (the restaurant) pays count.
 */
class MercadoPagoAdapterFeeTest {

    private final MercadoPagoAdapter adapter =
        new MercadoPagoAdapter(new MercadoPagoProperties("token", "secret", true, 0, 0, 0), null, null);

    @Test
    void feeIsTheSumOfTheFeesPaidByTheCollectorAndNetIsWhatTheSellerReceives() {
        Payment payment = payment(
            List.of(fee("mercadopago_fee", "collector", "49.99"),
                    fee("financing_fee", "collector", "10.005"),
                    fee("application_fee", "payer", "300.00")),
            "940.00");

        PaymentSnapshot snapshot = adapter.snapshotsNewestFirst(List.of(payment)).get(0);

        assertThat(snapshot.feeCents()).isEqualTo(6_000L); // 49.99 + 10.005 = 59.995 -> HALF_UP cents
        assertThat(snapshot.netReceivedCents()).isEqualTo(94_000L);
    }

    @Test
    void feeAndNetAreNullWhenMercadoPagoDoesNotReportThem() {
        Payment payment = payment(null, null);

        PaymentSnapshot snapshot = adapter.snapshotsNewestFirst(List.of(payment)).get(0);

        assertThat(snapshot.feeCents()).isNull();
        assertThat(snapshot.netReceivedCents()).isNull();
    }

    @Test
    void feeIsNullWhenNoFeeDetailBelongsToTheCollector() {
        Payment payment = payment(List.of(fee("application_fee", "payer", "3.00")), "100.00");

        PaymentSnapshot snapshot = adapter.snapshotsNewestFirst(List.of(payment)).get(0);

        assertThat(snapshot.feeCents()).isNull();
        assertThat(snapshot.netReceivedCents()).isEqualTo(10_000L);
    }

    private static Payment payment(List<PaymentFeeDetail> fees, String net) {
        Payment payment = new Payment();
        ReflectionTestUtils.setField(payment, "id", 7L);
        ReflectionTestUtils.setField(payment, "status", "approved");
        ReflectionTestUtils.setField(payment, "externalReference", "ref");
        ReflectionTestUtils.setField(payment, "feeDetails", fees);
        if (net != null) {
            PaymentTransactionDetails details = new PaymentTransactionDetails();
            ReflectionTestUtils.setField(details, "netReceivedAmount", new BigDecimal(net));
            ReflectionTestUtils.setField(payment, "transactionDetails", details);
        }
        return payment;
    }

    private static PaymentFeeDetail fee(String type, String payer, String amount) {
        PaymentFeeDetail fee = new PaymentFeeDetail();
        ReflectionTestUtils.setField(fee, "type", type);
        ReflectionTestUtils.setField(fee, "feePayer", payer);
        ReflectionTestUtils.setField(fee, "amount", new BigDecimal(amount));
        return fee;
    }
}
