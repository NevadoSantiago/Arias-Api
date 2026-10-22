package com.arias.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalización de celulares: no se exige una cantidad de dígitos. Sin
 * {@code +} se guarda como {@code +549} + dígitos; con {@code +} se guarda tal
 * cual. Solo dígitos, tras quitar separadores. Unicidad, sin OTP (diseño
 * §Decisión 8). Pura — sin Spring, sin base de datos.
 */
class PhoneNumbersTest {

    @Test
    void aceptaDiezDigitosYLosGuardaConPrefijo549() {
        Optional<String> normalized = PhoneNumbers.normalize("1159876547");

        assertThat(normalized).contains("+5491159876547");
    }

    @Test
    void quitaEspaciosYGuionesAntesDeValidar() {
        assertThat(PhoneNumbers.normalize("11 5987-6547")).contains("+5491159876547");
        assertThat(PhoneNumbers.normalize(" 11-5987 6547 ")).contains("+5491159876547");
    }

    @Test
    void aceptaCualquierCantidadDeDigitosSinMas() {
        assertThat(PhoneNumbers.normalize("115987654")).contains("+549115987654");
        assertThat(PhoneNumbers.normalize("11598765478")).contains("+54911598765478");
        assertThat(PhoneNumbers.normalize("1")).contains("+5491");
    }

    @Test
    void aceptaE164SinCambiarlo() {
        assertThat(PhoneNumbers.normalize("+5491159876547")).contains("+5491159876547");
        assertThat(PhoneNumbers.normalize("+59899123456")).contains("+59899123456");
    }

    @Test
    void quitaEspaciosYGuionesEnE164() {
        assertThat(PhoneNumbers.normalize("+54 9 11 5987-6547")).contains("+5491159876547");
        assertThat(PhoneNumbers.normalize(" +598 99-123 456 ")).contains("+59899123456");
    }

    @Test
    void rechazaE164ConLetras() {
        assertThat(PhoneNumbers.normalize("+54911abc6547")).isEmpty();
    }

    @Test
    void aceptaE164DeCualquierLargo() {
        assertThat(PhoneNumbers.normalize("+54911")).contains("+54911");
        assertThat(PhoneNumbers.normalize("+5491159876547123")).contains("+5491159876547123");
    }

    @Test
    void rechazaMasSinDigitos() {
        assertThat(PhoneNumbers.normalize("+")).isEmpty();
        assertThat(PhoneNumbers.normalize("+ - ")).isEmpty();
    }

    @Test
    void rechazaUnNumeroConLetras() {
        assertThat(PhoneNumbers.normalize("11598abc47")).isEmpty();
    }

    @Test
    void rechazaValorNuloOVacio() {
        assertThat(PhoneNumbers.normalize(null)).isEmpty();
        assertThat(PhoneNumbers.normalize("  ")).isEmpty();
    }

    @Test
    void rechazaUnNumeroQueNoEntraEnLaColumna() {
        // users.phone is VARCHAR(30): the +549 prefix pushes 27 local digits past it.
        assertThat(PhoneNumbers.normalize("1".repeat(26))).contains("+549" + "1".repeat(26));
        assertThat(PhoneNumbers.normalize("1".repeat(27))).isEmpty();
        assertThat(PhoneNumbers.normalize("+" + "1".repeat(29))).isPresent();
        assertThat(PhoneNumbers.normalize("+" + "1".repeat(30))).isEmpty();
    }
}
