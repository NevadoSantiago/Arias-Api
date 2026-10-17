package com.arias.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalización de celulares argentinos: el usuario tipea los 10 dígitos
 * (área + número, sin 0 ni 15) y se guarda como {@code +549} + 10 dígitos.
 * Formato + unicidad, sin OTP (diseño §Decisión 8). Pura — sin Spring, sin
 * base de datos.
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
    void rechazaMenosDeDiezDigitos() {
        assertThat(PhoneNumbers.normalize("115987654")).isEmpty();
    }

    @Test
    void rechazaMasDeDiezDigitos() {
        assertThat(PhoneNumbers.normalize("11598765478")).isEmpty();
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
    void rechazaE164MuyCortoOMuyLargo() {
        assertThat(PhoneNumbers.normalize("+54911")).isEmpty();
        assertThat(PhoneNumbers.normalize("+5491159876547123")).isEmpty();
    }

    @Test
    void rechazaE164QueEmpiezaConCero() {
        assertThat(PhoneNumbers.normalize("+05491159876547")).isEmpty();
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
}
