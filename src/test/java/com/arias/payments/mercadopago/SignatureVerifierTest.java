package com.arias.payments.mercadopago;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vectores válidos e inválidos del verificador HMAC de webhooks de Mercado
 * Pago — unidad 10, tarea 10.5. Los HMAC esperados se calculan en el propio
 * test con la misma fórmula que {@link SignatureVerifier}, nunca hardcodeados.
 */
class SignatureVerifierTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String OTHER_SECRET = "a-completely-different-secret";

    private final MercadoPagoProperties props = new MercadoPagoProperties("token", SECRET, true);
    private final SignatureVerifier verifier = new SignatureVerifier(props);

    @Test
    void validSignatureVerifies() {
        String dataId = "123456789";
        String requestId = "req-abc-123";
        String ts = "1700000000000";
        String header = signedHeader(dataId, requestId, ts, SECRET);

        assertThat(verifier.verify(header, requestId, dataId)).isTrue();
    }

    @Test
    void tamperedPayloadIdIsRejected() {
        String requestId = "req-abc-123";
        String ts = "1700000000000";
        // header firmado para un data.id distinto del que llega a verify()
        String header = signedHeader("111111111", requestId, ts, SECRET);

        assertThat(verifier.verify(header, requestId, "999999999")).isFalse();
    }

    @Test
    void wrongSecretIsRejected() {
        String dataId = "123456789";
        String requestId = "req-abc-123";
        String ts = "1700000000000";
        // firmado con un secreto distinto al configurado en `props`
        String header = signedHeader(dataId, requestId, ts, OTHER_SECRET);

        assertThat(verifier.verify(header, requestId, dataId)).isFalse();
    }

    @Test
    void missingXRequestIdOmitsComponentFromManifest() {
        String dataId = "123456789";
        String ts = "1700000000000";
        // sin x-request-id el componente `request-id:` se omite del manifiesto
        // (diseño §Seguridad) — el header se firma ya sin ese segmento.
        String manifest = "id:" + dataId.toLowerCase() + ";ts:" + ts + ";";
        String header = "ts=" + ts + ",v1=" + hmacHex(manifest, SECRET);

        assertThat(verifier.verify(header, null, dataId)).isTrue();
    }

    @Test
    void malformedHeaderIsRejected() {
        assertThat(verifier.verify("not-a-valid-signature-header", "req-1", "123")).isFalse();
        assertThat(verifier.verify("ts=1700000000000", "req-1", "123")).isFalse(); // falta v1
        assertThat(verifier.verify("v1=deadbeef", "req-1", "123")).isFalse();      // falta ts
        assertThat(verifier.verify("", "req-1", "123")).isFalse();
        assertThat(verifier.verify(null, "req-1", "123")).isFalse();
    }

    private String signedHeader(String dataId, String requestId, String ts, String secret) {
        String manifest = "id:" + dataId.toLowerCase() + ";request-id:" + requestId + ";ts:" + ts + ";";
        return "ts=" + ts + ",v1=" + hmacHex(manifest, secret);
    }

    private String hmacHex(String manifest, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
