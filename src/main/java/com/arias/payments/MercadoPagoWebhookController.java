package com.arias.payments;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook de Mercado Pago (unidad 11, tarea 11.4, diseño §Flujo de datos
 * "Compra con Mercado Pago", pasos 1–2). Público por necesidad — registrado
 * en {@code SecurityConfig} — pero la única puerta de entrada real es la
 * validación de firma HMAC, que corre ANTES de cualquier otro trabajo. Los
 * pasos 3–9 (consultar el pago como fuente de verdad, resolver la compra,
 * comparar importe, bloquear fila, mapear estado, commitear) viven en
 * {@link CreditPurchaseService#processPaymentNotification}.
 *
 * <p>Siempre responde 200 salvo firma inválida (401) — Mercado Pago
 * reintenta hasta 8 veces durante varios días ante cualquier respuesta que
 * no sea 2xx, así que un "no encontrado"/"importe no coincide" también se
 * responde 200 (ya quedó loggeado) para no generar reintentos inútiles;
 * solo una falla real de Mercado Pago al re-consultar el pago (502, ver
 * {@code MercadoPagoAdapter}) deja que la excepción se propague y dispare un
 * reintento legítimo.
 */
@RestController
@RequestMapping("/api/webhooks/mercadopago")
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoWebhookController {

    private final PaymentGateway paymentGateway;
    private final CreditPurchaseService purchaseService;

    /** Formato del webhook "moderno" de Mercado Pago — {@code type}/{@code data.id} en el cuerpo. */
    public record WebhookPayload(String type, String action, Data data) {
        public record Data(String id) {}
    }

    @PostMapping
    public ResponseEntity<Void> receive(
        @RequestHeader(value = "x-signature", required = false) String xSignature,
        @RequestHeader(value = "x-request-id", required = false) String xRequestId,
        @RequestBody(required = false) WebhookPayload payload
    ) {
        String dataId = payload != null && payload.data() != null ? payload.data().id() : null;

        // Paso 1: validar la firma ANTES de cualquier otro trabajo — firma
        // inválida rechaza sin acreditar (diseño §Seguridad).
        if (!paymentGateway.verifySignature(xSignature, xRequestId, dataId)) {
            log.warn("Webhook de Mercado Pago con firma inválida — rechazado sin acreditar");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Paso 2: solo nos interesan las notificaciones de tipo "payment".
        String topic = payload != null ? payload.type() : null;
        if (!"payment".equals(topic)) {
            return ResponseEntity.ok().build();
        }

        if (dataId == null || dataId.isBlank()) {
            log.warn("Webhook de Mercado Pago de tipo payment sin data.id — ignorado");
            return ResponseEntity.ok().build();
        }

        purchaseService.processPaymentNotification(dataId);
        return ResponseEntity.ok().build();
    }
}
