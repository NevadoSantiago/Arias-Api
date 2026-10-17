package com.arias.auth;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalización de teléfonos celulares — diseño §Decisión 8: formato +
 * unicidad de {@code users.phone}, sin verificación OTP. El frontend envía
 * E.164 armado desde un selector de país (AR por defecto, que agrega el
 * {@code 9} móvil); ese valor se guarda tal cual. Para clientes anteriores se
 * sigue aceptando la forma local de 10 dígitos (área + número, sin 0 ni 15),
 * que se guarda como {@code +549} + 10 dígitos. Pura, sin efectos secundarios.
 */
public final class PhoneNumbers {

    /** Prefijo de celular argentino en E.164 (país 54 + móvil 9). */
    private static final String AR_MOBILE_PREFIX = "+549";

    /** Exactamente 10 dígitos (código de área + número). */
    private static final Pattern TEN_DIGITS = Pattern.compile("^\\d{10}$");

    /** E.164: {@code +}, primer dígito 1-9, 7 a 15 dígitos en total. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    /** Separadores visuales tolerados: espacios, guiones, paréntesis y puntos. */
    private static final Pattern VISUAL_SEPARATORS = Pattern.compile("[\\s\\-().]");

    private PhoneNumbers() {
    }

    /**
     * Quita separadores visuales y devuelve el número guardable. Con prefijo
     * {@code +} exige E.164 y lo devuelve sin cambios; sin {@code +} exige
     * exactamente 10 dígitos y devuelve {@code +549} + 10 dígitos. Devuelve
     * vacío si el valor es nulo, en blanco o no cumple el formato — el caller
     * decide cómo responder (ej. {@code BusinessException.badRequest}).
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String stripped = VISUAL_SEPARATORS.matcher(raw.trim()).replaceAll("");
        if (stripped.startsWith("+")) {
            return E164.matcher(stripped).matches() ? Optional.of(stripped) : Optional.empty();
        }
        return TEN_DIGITS.matcher(stripped).matches()
            ? Optional.of(AR_MOBILE_PREFIX + stripped)
            : Optional.empty();
    }
}
