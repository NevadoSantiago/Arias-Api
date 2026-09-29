package com.arias.payments.mercadopago;

import com.arias.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit B13.2 — the gateway's fail-fast check used before any checkout row is created. */
class MercadoPagoAdapterAvailabilityTest {

    @Test
    void requireAvailableFailsWith503WhenMercadoPagoIsDisabled() {
        var adapter = new MercadoPagoAdapter(new MercadoPagoProperties("", "", false, 0, 0, 0), null, null);

        assertThatThrownBy(adapter::requireAvailable)
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo("mercadopago-disabled");
                org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });
    }

    @Test
    void requireAvailablePassesWhenConfigured() {
        var adapter = new MercadoPagoAdapter(new MercadoPagoProperties("token", "secret", true, 0, 0, 0), null, null);

        assertThatCode(adapter::requireAvailable).doesNotThrowAnyException();
    }
}
