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
 * <p>Timeouts (unidad B13.1): el SDK usa 20 s por defecto en los tres, demasiado
 * para un pedido de cliente que espera la respuesta. Se aplican con {@code
 * MercadoPagoConfig.setConnectionTimeout/setConnectionRequestTimeout/
 * setSocketTimeout} (milisegundos) al arrancar el adaptador. Un valor no
 * positivo vuelve al default: en Apache HttpClient 0 significa "infinito", que
 * es justo lo que hay que evitar. Los defaults viven SOLO acá ({@code DEFAULT_*_MS}
 * y el constructor compacto): ni {@code @DefaultValue} ni {@code application.yml}
 * repiten los números; el yml sólo expone las variables de entorno con {@code 0}
 * ("sin definir"), que el constructor convierte en el default.
 *
 * @param accessToken               credencial privada del vendedor en Mercado Pago
 * @param webhookSecret             secreto usado para validar la firma HMAC del webhook
 * @param enabled                   interruptor general de la integración
 * @param connectTimeoutMs          tiempo máximo para abrir la conexión (default 5000)
 * @param connectionRequestTimeoutMs tiempo máximo esperando una conexión libre del pool del SDK (default 5000)
 * @param socketTimeoutMs           tiempo máximo sin recibir datos, o sea la espera de la respuesta (default 10000)
 */
@ConfigurationProperties(prefix = "arias.mercadopago")
public record MercadoPagoProperties(
    String accessToken,
    String webhookSecret,
    boolean enabled,
    int connectTimeoutMs,
    int connectionRequestTimeoutMs,
    int socketTimeoutMs
) {

    static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    static final int DEFAULT_SOCKET_TIMEOUT_MS = 10_000;
    static final int DEFAULT_CONNECTION_REQUEST_TIMEOUT_MS = 5_000;

    public MercadoPagoProperties {
        connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        socketTimeoutMs = socketTimeoutMs > 0 ? socketTimeoutMs : DEFAULT_SOCKET_TIMEOUT_MS;
        connectionRequestTimeoutMs = connectionRequestTimeoutMs > 0
            ? connectionRequestTimeoutMs : DEFAULT_CONNECTION_REQUEST_TIMEOUT_MS;
    }

    public boolean isConfigured() {
        return enabled && notBlank(accessToken) && notBlank(webhookSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
