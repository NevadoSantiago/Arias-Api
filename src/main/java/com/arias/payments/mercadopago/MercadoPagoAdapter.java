package com.arias.payments.mercadopago;

import com.arias.common.config.PublicUrlProperties;
import com.arias.common.exception.BusinessException;
import com.arias.payments.CheckoutRequest;
import com.arias.payments.CheckoutSession;
import com.arias.payments.PaymentGateway;
import com.arias.payments.PaymentSnapshot;
import com.arias.payments.PaymentStatus;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPResultsResourcesPage;
import com.mercadopago.net.MPSearchRequest;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptador de {@link PaymentGateway} sobre el SDK oficial de Mercado Pago —
 * Checkout Pro vía la Preferences API ({@link PreferenceClient#create} +
 * {@link PaymentClient#get}). Único punto del código base donde viven los
 * tipos {@code com.mercadopago.*} (diseño §Enfoque técnico).
 *
 * <p>Deliberadamente NO usa {@code OrderClient}/Orders API — es un producto
 * distinto con otro modelo de estados (investigación #607).
 *
 * <p>Inerte si {@link MercadoPagoProperties#isConfigured()} es {@code false}:
 * no hace ninguna llamada de red al arrancar, y cada operación de pago
 * responde con un error 503 claro en vez de romper el arranque de la app
 * (mismo patrón que {@code R2Config}/{@code UploadService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoAdapter implements PaymentGateway {

    /** Mercado Pago opera en pesos con 2 decimales; nuestro dominio usa centavos enteros. */
    private static final int CENTS_PER_UNIT = 100;
    private static final String CURRENCY_ID = "ARS";

    private final MercadoPagoProperties props;
    private final PublicUrlProperties publicUrlProps;
    private final SignatureVerifier signatureVerifier;

    /**
     * {@code MercadoPagoConfig.setAccessToken} es estático y global al SDK —
     * se setea una sola vez al arrancar, sin hacer ninguna llamada de red, así
     * que no rompe {@code AriasApplicationTests} aunque no haya credenciales.
     */
    @PostConstruct
    void init() {
        if (props.isConfigured()) {
            MercadoPagoConfig.setAccessToken(props.accessToken());
            log.info("Mercado Pago configurado (checkout habilitado)");
        } else {
            log.info("Mercado Pago deshabilitado o sin configurar (arias.mercadopago.enabled=false)");
        }
    }

    @Override
    public CheckoutSession createCheckout(CheckoutRequest request) {
        requireConfigured();

        PreferenceItemRequest item = PreferenceItemRequest.builder()
            .title(request.title())
            .quantity(request.quantity())
            .unitPrice(centsToAmount(request.unitPriceCents()))
            .currencyId(CURRENCY_ID)
            .build();

        PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
            .success(request.successUrl())
            .pending(request.pendingUrl())
            .failure(request.failureUrl())
            .build();

        PreferenceRequest.PreferenceRequestBuilder builder = PreferenceRequest.builder()
            .items(List.of(item))
            .externalReference(request.externalReference())
            .backUrls(backUrls)
            .notificationUrl(request.notificationUrl());

        // auto_return="approved" requiere back_urls.success y Mercado Pago la
        // rechaza si no es https / si es localhost — solo la activamos si el
        // frontend público es https (precedente CeroComa, diseño §Configuración).
        if (isHttps(publicUrlProps.frontendUrl())) {
            builder.autoReturn("approved");
        }

        try {
            Preference preference = new PreferenceClient().create(builder.build());
            return new CheckoutSession(preference.getId(), preference.getInitPoint());
        } catch (MPApiException e) {
            log.error("Mercado Pago rechazó la creación de la preferencia: status={} body={}",
                e.getStatusCode(), apiResponseBody(e));
            throw checkoutFailed();
        } catch (MPException e) {
            log.error("Error de red/SDK al crear la preferencia de Mercado Pago", e);
            throw checkoutFailed();
        }
    }

    @Override
    public PaymentSnapshot getPayment(String paymentId) {
        requireConfigured();
        try {
            Payment payment = new PaymentClient().get(Long.valueOf(paymentId));
            return toSnapshot(payment);
        } catch (NumberFormatException e) {
            throw BusinessException.badRequest("mercadopago-invalid-payment-id",
                "El id de pago de Mercado Pago no es válido: " + paymentId);
        } catch (MPApiException e) {
            log.error("Mercado Pago rechazó la consulta del pago {}: status={} body={}",
                paymentId, e.getStatusCode(), apiResponseBody(e));
            throw paymentLookupFailed();
        } catch (MPException e) {
            log.error("Error de red/SDK al consultar el pago {} en Mercado Pago", paymentId, e);
            throw paymentLookupFailed();
        }
    }

    @Override
    public boolean verifySignature(String xSignature, String xRequestId, String dataId) {
        return signatureVerifier.verify(xSignature, xRequestId, dataId);
    }

    /**
     * {@code PaymentReconciliationScheduler} (unidad 11): re-consulta por
     * {@code external_reference} cuando el webhook nunca llegó, así que
     * todavía no tenemos el {@code payment_id}. Puede haber más de un intento
     * de pago para la misma referencia (reintentos del usuario en Checkout
     * Pro) — nos quedamos con el más reciente por {@code dateCreated}.
     */
    @Override
    public Optional<PaymentSnapshot> findByExternalReference(String externalReference) {
        requireConfigured();
        MPSearchRequest request = MPSearchRequest.builder()
            .filters(Map.of("external_reference", externalReference))
            .build();
        try {
            MPResultsResourcesPage<Payment> page = new PaymentClient().search(request);
            if (page == null || page.getResults() == null || page.getResults().isEmpty()) {
                return Optional.empty();
            }
            return page.getResults().stream()
                .max(Comparator.comparing(Payment::getDateCreated,
                    Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(this::toSnapshot);
        } catch (MPApiException e) {
            log.error("Mercado Pago rechazó la búsqueda por external_reference {}: status={} body={}",
                externalReference, e.getStatusCode(), apiResponseBody(e));
            throw paymentLookupFailed();
        } catch (MPException e) {
            log.error("Error de red/SDK al buscar pagos por external_reference {} en Mercado Pago",
                externalReference, e);
            throw paymentLookupFailed();
        }
    }

    private PaymentSnapshot toSnapshot(Payment payment) {
        long amountCents = payment.getTransactionAmount() == null
            ? 0L
            : amountToCents(payment.getTransactionAmount());
        long amountRefundedCents = payment.getTransactionAmountRefunded() == null
            ? 0L
            : amountToCents(payment.getTransactionAmountRefunded());
        return new PaymentSnapshot(
            String.valueOf(payment.getId()),
            PaymentStatus.fromMercadoPago(payment.getStatus()),
            payment.getStatusDetail(),
            amountCents,
            payment.getCurrencyId(),
            payment.getExternalReference(),
            amountRefundedCents
        );
    }

    private BigDecimal centsToAmount(long cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(CENTS_PER_UNIT), 2, RoundingMode.HALF_UP);
    }

    private long amountToCents(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(CENTS_PER_UNIT)).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private boolean isHttps(String url) {
        return url != null && url.toLowerCase().startsWith("https://");
    }

    private String apiResponseBody(MPApiException e) {
        return e.getApiResponse() != null ? e.getApiResponse().getContent() : null;
    }

    private void requireConfigured() {
        if (!props.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "mercadopago-disabled",
                "La integración con Mercado Pago no está configurada.");
        }
    }

    private BusinessException checkoutFailed() {
        return new BusinessException(HttpStatus.BAD_GATEWAY, "mercadopago-checkout-failed",
            "No se pudo iniciar el pago con Mercado Pago.");
    }

    private BusinessException paymentLookupFailed() {
        return new BusinessException(HttpStatus.BAD_GATEWAY, "mercadopago-payment-lookup-failed",
            "No se pudo consultar el pago en Mercado Pago.");
    }
}
