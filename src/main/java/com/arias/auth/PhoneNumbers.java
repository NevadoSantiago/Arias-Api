package com.arias.auth;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalización de teléfonos celulares — diseño §Decisión 8: unicidad de
 * {@code users.phone}, sin verificación OTP. No se exige una cantidad de
 * dígitos: solo que haya al menos uno y que todo sea numérico. El frontend
 * envía el valor armado desde un selector de país (AR por defecto, que agrega
 * el {@code 9} móvil); con {@code +} se guarda tal cual. Para clientes
 * anteriores, dígitos sin {@code +} se guardan como {@code +549} + dígitos.
 * Pura, sin efectos secundarios.
 */
public final class PhoneNumbers {

    /** Prefijo de celular argentino en E.164 (país 54 + móvil 9). */
    private static final String AR_MOBILE_PREFIX = "+549";

    /** Uno o más dígitos, sin prefijo. */
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    /** {@code +} seguido de uno o más dígitos. */
    private static final Pattern PLUS_DIGITS = Pattern.compile("^\\+\\d+$");

    /** Largo de la columna {@code users.phone} (VARCHAR(30)): no es una regla de formato. */
    private static final int MAX_STORED_LENGTH = 30;

    /** Separadores visuales tolerados: espacios, guiones, paréntesis y puntos. */
    private static final Pattern VISUAL_SEPARATORS = Pattern.compile("[\\s\\-().]");

    private PhoneNumbers() {
    }

    /**
     * Quita separadores visuales y devuelve el número guardable. Con prefijo
     * {@code +} exige uno o más dígitos y lo devuelve sin cambios; sin {@code +}
     * exige uno o más dígitos y devuelve {@code +549} + dígitos. Devuelve
     * vacío si el valor es nulo, en blanco, no cumple el formato o no entra en
     * la columna — el caller
     * decide cómo responder (ej. {@code BusinessException.badRequest}).
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String stripped = VISUAL_SEPARATORS.matcher(raw.trim()).replaceAll("");
        Optional<String> phone;
        if (stripped.startsWith("+")) {
            phone = PLUS_DIGITS.matcher(stripped).matches() ? Optional.of(stripped) : Optional.empty();
        } else {
            phone = DIGITS.matcher(stripped).matches()
                ? Optional.of(AR_MOBILE_PREFIX + stripped)
                : Optional.empty();
        }
        return phone.filter(p -> p.length() <= MAX_STORED_LENGTH);
    }
}
