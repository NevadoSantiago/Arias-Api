package com.arias.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalización de teléfonos a E.164 (diseño §Decisión 8: formato + unicidad,
 * sin OTP). Pura — sin Spring, sin base de datos.
 */
class PhoneNumbersTest {

    @Test
    void aceptaUnNumeroYaEnFormatoE164() {
        Optional<String> normalized = PhoneNumbers.normalizeE164("+5491122334455");

        assertThat(normalized).contains("+5491122334455");
    }

    @Test
    void quitaEspaciosGuionesYParentesisAntesDeValidar() {
        Optional<String> normalized = PhoneNumbers.normalizeE164("+54 (911) 2233-4455");

        assertThat(normalized).contains("+5491122334455");
    }

    @Test
    void rechazaUnNumeroSinSignoMas() {
        Optional<String> normalized = PhoneNumbers.normalizeE164("5491122334455");

        assertThat(normalized).isEmpty();
    }

    @Test
    void rechazaUnNumeroDemasiadoCorto() {
        Optional<String> normalized = PhoneNumbers.normalizeE164("+5491");

        assertThat(normalized).isEmpty();
    }

    @Test
    void rechazaUnNumeroConLetras() {
        Optional<String> normalized = PhoneNumbers.normalizeE164("+549abc2334455");

        assertThat(normalized).isEmpty();
    }

    @Test
    void rechazaValorNuloOVacio() {
        assertThat(PhoneNumbers.normalizeE164(null)).isEmpty();
        assertThat(PhoneNumbers.normalizeE164("  ")).isEmpty();
    }
}
