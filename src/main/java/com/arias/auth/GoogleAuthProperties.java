package com.arias.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config de Google Sign-In — se mapea desde {@code arias.google.*} en
 * application.yml (diseño §Configuración).
 *
 * @param clientId client ID de OAuth 2.0 de Google, usado como audiencia
 *                 esperada al validar el ID token ({@link
 *                 com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier},
 *                 diseño §Decisión 9). Debe ser EL MISMO valor que usa el
 *                 frontend como client ID de {@code @react-oauth/google}.
 */
@ConfigurationProperties(prefix = "arias.google")
public record GoogleAuthProperties(String clientId) {}
