package com.arias.users;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nombre con el que el mostrador llama al cliente: apodo, luego nombre y
 * apellido, luego el correo. Lo comparten la comanda del cliente ({@code
 * /auth/me}) y la etiqueta de cocina, así ambos ven el mismo nombre.
 */
class UserDisplayNameTest {

    private static User user(String nickname, String first, String last) {
        return User.builder().email("ana@test.arias.com")
            .nickname(nickname).firstName(first).lastName(last).build();
    }

    @Test
    @DisplayName("displayName(): uses the nickname when present")
    void usaElApodo() {
        assertThat(user("Coty", "Ana", "Perez").displayName()).isEqualTo("Coty");
    }

    @Test
    @DisplayName("displayName(): falls back to first and last name when the nickname is blank")
    void caeANombreYApellido() {
        assertThat(user("  ", "Ana", "Perez").displayName()).isEqualTo("Ana Perez");
        assertThat(user(null, "Ana", null).displayName()).isEqualTo("Ana");
        assertThat(user(null, null, "Perez").displayName()).isEqualTo("Perez");
    }

    @Test
    @DisplayName("displayName(): falls back to the email when there is no name")
    void caeAlCorreo() {
        assertThat(user(null, null, null).displayName()).isEqualTo("ana@test.arias.com");
        assertThat(user("", " ", "").displayName()).isEqualTo("ana@test.arias.com");
    }
}
