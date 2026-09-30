package com.arias.auth;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalización de celulares argentinos — diseño §Decisión 8: formato +
 * unicidad de {@code users.phone}, sin verificación OTP. El usuario tipea los
 * 10 dígitos (área + número, sin 0 ni 15) y se guarda como {@code +549} + 10
 * dígitos. Pura, sin efectos secundarios.
 */
public final class PhoneNumbers {

    /** Prefijo de celular argentino en E.164 (país 54 + móvil 9). */
    private static final String AR_MOBILE_PREFIX = "+549";

    /** Exactamente 10 dígitos (código de área + número). */
    private static final Pattern TEN_DIGITS = Pattern.compile("^\\d{10}$");

    /** Separadores visuales tolerados en la entrada cruda: espacios y guiones. */
    private static final Pattern VISUAL_SEPARATORS = Pattern.compile("[\\s-]");

    private PhoneNumbers() {
    }

    /**
     * Quita espacios y guiones, exige exactamente 10 dígitos y devuelve el
     * número guardable ({@code +549} + 10 dígitos). Devuelve vacío si el valor
     * es nulo, en blanco o no cumple el formato — el caller decide cómo
     * responder (ej. {@code BusinessException.badRequest}).
     */
    public static Optional<String> normalizeArMobile(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String stripped = VISUAL_SEPARATORS.matcher(raw.trim()).replaceAll("");
        return TEN_DIGITS.matcher(stripped).matches()
            ? Optional.of(AR_MOBILE_PREFIX + stripped)
            : Optional.empty();
    }
}
