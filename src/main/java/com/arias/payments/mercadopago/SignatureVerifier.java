package com.arias.payments.mercadopago;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Verifica la firma HMAC-SHA256 del header {@code x-signature} que Mercado
 * Pago adjunta a cada webhook (investigación #607, diseño §Seguridad).
 *
 * <p>Formato del header: {@code ts=<epoch ms>,v1=<hex hmac>}. El manifiesto
 * firmado es {@code id:<data.id en minúsculas>;request-id:<x-request-id>;ts:<ts>;} —
 * cualquier componente cuyo valor de origen esté ausente se OMITE (no se
 * agrega con valor vacío). La comparación contra el hash recibido es en
 * tiempo constante ({@link MessageDigest#isEqual}).
 *
 * <p>Esta unidad solo cubre el verificador; conectarlo a un endpoint de
 * webhook es responsabilidad de la unidad 11.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final MercadoPagoProperties props;

    /**
     * @param xSignature valor crudo del header {@code x-signature}
     * @param xRequestId valor crudo del header {@code x-request-id}; puede ser {@code null}
     * @param dataId     {@code data.id} del payload del webhook; puede ser {@code null}
     * @return {@code true} solo si el header está bien formado y el HMAC coincide
     */
    public boolean verify(String xSignature, String xRequestId, String dataId) {
        if (xSignature == null || xSignature.isBlank()) {
            return false;
        }

        Map<String, String> parts = parseHeader(xSignature);
        String ts = parts.get("ts");
        String v1 = parts.get("v1");
        if (ts == null || ts.isBlank() || v1 == null || v1.isBlank()) {
            log.warn("Webhook de Mercado Pago con header x-signature malformado");
            return false;
        }

        String manifest = buildManifest(dataId, xRequestId, ts);
        String expectedHex;
        try {
            expectedHex = hmacHex(manifest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("No se pudo calcular el HMAC del webhook de Mercado Pago", e);
            return false;
        }

        return constantTimeEquals(expectedHex, v1);
    }

    private Map<String, String> parseHeader(String xSignature) {
        Map<String, String> parts = new HashMap<>();
        for (String segment : xSignature.split(",")) {
            int eq = segment.indexOf('=');
            if (eq <= 0 || eq == segment.length() - 1) {
                continue;
            }
            String key = segment.substring(0, eq).trim();
            String value = segment.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                parts.put(key, value);
            }
        }
        return parts;
    }

    private String buildManifest(String dataId, String xRequestId, String ts) {
        StringBuilder manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            manifest.append("id:").append(dataId.toLowerCase()).append(';');
        }
        if (xRequestId != null && !xRequestId.isBlank()) {
            manifest.append("request-id:").append(xRequestId).append(';');
        }
        manifest.append("ts:").append(ts).append(';');
        return manifest.toString();
    }

    private String hmacHex(String manifest) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(props.webhookSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        byte[] hash = mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private boolean constantTimeEquals(String expectedHex, String actualHex) {
        return MessageDigest.isEqual(
            expectedHex.toLowerCase().getBytes(StandardCharsets.UTF_8),
            actualHex.toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }
}
