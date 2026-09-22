package com.arias.payments.mercadopago;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config de Mercado Pago — se mapea desde {@code arias.mercadopago.*} en
 * application.yml (diseño §Configuración, patrón R2/Resend).
 *
 * <p>Si {@code enabled} es {@code false} o faltan {@code accessToken}/
 * {@code webhookSecret}, {@link #isConfigured()} devuelve {@code false} y
 * {@link MercadoPagoAdapter} responde con un error claro en vez de intentar
 * llamar a la API (mismo patrón que {@code R2Properties}/{@code UploadService}).
 *
 * @param accessToken   credencial privada del vendedor en Mercado Pago
 * @param webhookSecret secreto usado para validar la firma HMAC del webhook
 * @param enabled       interruptor general de la integración
 */
@ConfigurationProperties(prefix = "arias.mercadopago")
public record MercadoPagoProperties(String accessToken, String webhookSecret, boolean enabled) {

    public boolean isConfigured() {
        return enabled && notBlank(accessToken) && notBlank(webhookSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
