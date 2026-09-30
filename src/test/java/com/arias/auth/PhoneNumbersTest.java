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
        Optional<String> normalized = PhoneNumbers.normalizeArMobile("1159876547");

        assertThat(normalized).contains("+5491159876547");
    }

    @Test
    void quitaEspaciosYGuionesAntesDeValidar() {
        assertThat(PhoneNumbers.normalizeArMobile("11 5987-6547")).contains("+5491159876547");
        assertThat(PhoneNumbers.normalizeArMobile(" 11-5987 6547 ")).contains("+5491159876547");
    }

    @Test
    void rechazaMenosDeDiezDigitos() {
        assertThat(PhoneNumbers.normalizeArMobile("115987654")).isEmpty();
    }

    @Test
    void rechazaMasDeDiezDigitos() {
        assertThat(PhoneNumbers.normalizeArMobile("11598765478")).isEmpty();
    }

    @Test
    void rechazaElFormatoInternacionalConSignoMas() {
        assertThat(PhoneNumbers.normalizeArMobile("+5491159876547")).isEmpty();
        assertThat(PhoneNumbers.normalizeArMobile("+54 9 11 5987-6547")).isEmpty();
    }

    @Test
    void rechazaUnNumeroConLetras() {
        assertThat(PhoneNumbers.normalizeArMobile("11598abc47")).isEmpty();
    }

    @Test
    void rechazaValorNuloOVacio() {
        assertThat(PhoneNumbers.normalizeArMobile(null)).isEmpty();
        assertThat(PhoneNumbers.normalizeArMobile("  ")).isEmpty();
    }
}
