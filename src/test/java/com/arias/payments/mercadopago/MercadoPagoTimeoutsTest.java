package com.arias.payments.mercadopago;

import com.mercadopago.MercadoPagoConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unidad B13.1 — the Mercado Pago SDK defaults to 20 s for connect, connection
 * request and socket timeouts (verified in sdk-java 3.7.0, {@code
 * MercadoPagoConfig}); a hanging Mercado Pago must not hold a request thread
 * that long. The timeouts are configurable under {@code arias.mercadopago} and
 * applied to the SDK when the adapter starts.
 */
class MercadoPagoTimeoutsTest {

    @Configuration
    @EnableConfigurationProperties(MercadoPagoProperties.class)
    static class PropsConfig {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PropsConfig.class);

    private int savedConnect;
    private int savedRequest;
    private int savedSocket;

    @BeforeEach
    void saveSdkGlobals() {
        savedConnect = MercadoPagoConfig.getConnectionTimeout();
        savedRequest = MercadoPagoConfig.getConnectionRequestTimeout();
        savedSocket = MercadoPagoConfig.getSocketTimeout();
    }

    @AfterEach
    void restoreSdkGlobals() {
        MercadoPagoConfig.setConnectionTimeout(savedConnect);
        MercadoPagoConfig.setConnectionRequestTimeout(savedRequest);
        MercadoPagoConfig.setSocketTimeout(savedSocket);
    }

    @Test
    void timeoutsDefaultToFiveSecondsConnectAndTenSecondsSocket() {
        runner.run(ctx -> {
            MercadoPagoProperties props = ctx.getBean(MercadoPagoProperties.class);
            assertThat(props.connectTimeoutMs()).isEqualTo(5_000);
            assertThat(props.connectionRequestTimeoutMs()).isEqualTo(5_000);
            assertThat(props.socketTimeoutMs()).isEqualTo(10_000);
        });
    }

    @Test
    void timeoutsBindFromAriasMercadopagoProperties() {
        runner.withPropertyValues(
                "arias.mercadopago.connect-timeout-ms=1500",
                "arias.mercadopago.connection-request-timeout-ms=2500",
                "arias.mercadopago.socket-timeout-ms=7000")
            .run(ctx -> {
                MercadoPagoProperties props = ctx.getBean(MercadoPagoProperties.class);
                assertThat(props.connectTimeoutMs()).isEqualTo(1_500);
                assertThat(props.connectionRequestTimeoutMs()).isEqualTo(2_500);
                assertThat(props.socketTimeoutMs()).isEqualTo(7_000);
            });
    }

    @Test
    void nonPositiveTimeoutsFallBackToTheDefaultsInsteadOfMeaningInfinite() {
        MercadoPagoProperties props = new MercadoPagoProperties("t", "s", true, 0, -1, 0);

        assertThat(props.connectTimeoutMs()).isEqualTo(5_000);
        assertThat(props.connectionRequestTimeoutMs()).isEqualTo(5_000);
        assertThat(props.socketTimeoutMs()).isEqualTo(10_000);
    }

    @Test
    void adapterAppliesTheTimeoutsToTheSdk() {
        MercadoPagoProperties props = new MercadoPagoProperties("token", "secret", true, 1_500, 2_500, 7_000);
        MercadoPagoAdapter adapter = new MercadoPagoAdapter(props, null, null);

        adapter.init();

        assertThat(MercadoPagoConfig.getConnectionTimeout()).isEqualTo(1_500);
        assertThat(MercadoPagoConfig.getConnectionRequestTimeout()).isEqualTo(2_500);
        assertThat(MercadoPagoConfig.getSocketTimeout()).isEqualTo(7_000);
    }

    @Test
    void adapterAppliesTheTimeoutsEvenWhenMercadoPagoIsDisabled() {
        // Same SDK statics regardless of the token: never leave the 20 s default behind.
        MercadoPagoProperties props = new MercadoPagoProperties("", "", false, 1_000, 1_000, 3_000);
        new MercadoPagoAdapter(props, null, null).init();

        assertThat(MercadoPagoConfig.getSocketTimeout()).isEqualTo(3_000);
    }
}
