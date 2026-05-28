package com.arias.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Config de JWT — se mapea desde {@code arias.jwt.*} en application.yml.
 *
 * @param secret           clave HMAC para firmar tokens (mín 32 chars). En prod, sobrescribir via env var.
 * @param accessTokenTtl   duración del access token (típicamente 15 min)
 * @param refreshTokenTtl  duración del refresh token (típicamente 7 días)
 */
@ConfigurationProperties(prefix = "arias.jwt")
public record JwtProperties(
    String secret,
    Duration accessTokenTtl,
    Duration refreshTokenTtl
) {}
