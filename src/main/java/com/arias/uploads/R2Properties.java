package com.arias.uploads;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración de Cloudflare R2 (S3-compatible).
 *
 * <p>Los campos sensibles ({@code accessKeyId}, {@code secretAccessKey}, {@code endpoint})
 * deben venir de {@code application-local-old.yml} (gitignored) o variables de entorno.
 * Si están vacíos, el bean {@link R2Config} no se levanta y el endpoint de upload
 * responde 503.
 */
@ConfigurationProperties(prefix = "arias.r2")
public record R2Properties(
    String endpoint,
    String accessKeyId,
    String secretAccessKey,
    String bucket,
    String publicUrl,
    Duration presignTtl
) {
    public boolean isConfigured() {
        return notBlank(endpoint)
            && notBlank(accessKeyId)
            && notBlank(secretAccessKey)
            && notBlank(bucket)
            && notBlank(publicUrl);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
