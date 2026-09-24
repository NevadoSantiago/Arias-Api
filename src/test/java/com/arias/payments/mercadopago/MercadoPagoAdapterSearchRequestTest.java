package com.arias.payments.mercadopago;

import com.mercadopago.net.MPSearchRequest;
import com.mercadopago.net.UrlFormatter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconciliation search by {@code external_reference} must be a request
 * the SDK can turn into a URL. {@code MPSearchRequest.getParameters()} always
 * adds {@code limit} and {@code offset}, and {@code UrlFormatter} throws a
 * {@code NullPointerException} when either is null — found against the real
 * Mercado Pago sandbox, invisible to tests that use the fake gateway.
 */
class MercadoPagoAdapterSearchRequestTest {

    private static final String SEARCH_PATH = "/v1/payments/search";

    @Test
    void searchByExternalReferenceHasNoNullQueryParameters() {
        MPSearchRequest request = MercadoPagoAdapter.searchByExternalReference("purchase-123");

        assertThat(request.getParameters()).doesNotContainValue(null);
    }

    @Test
    void searchByExternalReferenceFormatsIntoASdkUrl() throws Exception {
        MPSearchRequest request = MercadoPagoAdapter.searchByExternalReference("purchase-123");

        String url = UrlFormatter.format(SEARCH_PATH, request.getParameters());

        assertThat(url).contains("external_reference=purchase-123");
    }
}
