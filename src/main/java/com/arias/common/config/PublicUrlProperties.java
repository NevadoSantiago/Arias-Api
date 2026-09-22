package com.arias.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * URLs públicas de frontend/backend, usadas por integraciones externas que
 * necesitan URLs absolutas (back_urls y notification_url de Mercado Pago,
 * unidad 10 — diseño §Configuración).
 *
 * @param frontendUrl base pública del frontend. Mercado Pago exige HTTPS y
 *                     rechaza {@code localhost} para {@code back_urls}; el
 *                     adaptador solo activa {@code auto_return} cuando este
 *                     valor empieza con {@code https://}.
 * @param backendUrl  base pública del backend, usada para construir la
 *                     {@code notification_url} del webhook.
 */
@ConfigurationProperties(prefix = "arias.public")
public record PublicUrlProperties(String frontendUrl, String backendUrl) {}
