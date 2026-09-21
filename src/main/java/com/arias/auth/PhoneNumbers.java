package com.arias.auth;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalización de teléfonos a E.164 — diseño §Decisión 8: formato + unicidad
 * de {@code users.phone}, sin verificación OTP. Pura, sin efectos secundarios.
 */
public final class PhoneNumbers {

    /** {@code +} seguido de 8 a 15 dígitos (E.164), el primero distinto de cero. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    /** Separadores visuales tolerados en la entrada cruda: espacios, guiones y paréntesis. */
    private static final Pattern VISUAL_SEPARATORS = Pattern.compile("[\\s().-]");

    private PhoneNumbers() {
    }

    /**
     * Quita separadores visuales y valida el formato E.164 resultante.
     * Devuelve vacío si el valor es nulo, en blanco o no cumple el formato
     * — el caller decide cómo responder (ej. {@code BusinessException.badRequest}).
     */
    public static Optional<String> normalizeE164(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String stripped = VISUAL_SEPARATORS.matcher(raw.trim()).replaceAll("");
        return E164.matcher(stripped).matches() ? Optional.of(stripped) : Optional.empty();
    }
}
