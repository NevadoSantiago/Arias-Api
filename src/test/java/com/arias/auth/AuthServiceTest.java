package com.arias.auth;

import com.arias.auth.dto.FirstLoginRequest;
import com.arias.common.exception.InvalidCredentialsException;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regresión B2B tras la migración V16 (spec {@code self-registration},
 * "Coexistencia con el flujo de lista blanca de empresas"): login normal y
 * el flujo {@code first-login} existente deben seguir funcionando exactamente
 * igual — las columnas nuevas (phone/nickname/emailVerifiedAt/googleSub) son
 * todas nullable y no participan de ninguno de los dos flujos.
 */
@SpringBootTest
@Transactional
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void loginSigueFuncionandoParaUnUsuarioConPasswordYaSeteado() {
        String email = "b2b-login-" + System.nanoTime() + "@test.arias.com";
        User user = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode("clave-segura-123"))
            .role(Role.EMPLOYEE)
            .active(true)
            .build();
        userRepo.save(user);

        AuthService.AuthResult result = authService.login(email, "clave-segura-123");

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshTokenValue()).isNotBlank();
    }

    @Test
    void loginRechazaPasswordIncorrectaIgualQueAntes() {
        String email = "b2b-login-bad-" + System.nanoTime() + "@test.arias.com";
        User user = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode("clave-segura-123"))
            .role(Role.EMPLOYEE)
            .active(true)
            .build();
        userRepo.save(user);

        assertThatThrownBy(() -> authService.login(email, "clave-incorrecta"))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void firstLoginSigueActivandoUnaCuentaWhitelisteadaSinPassword() {
        String email = "b2b-first-login-" + System.nanoTime() + "@test.arias.com";
        User whitelisted = User.builder()
            .email(email)
            .role(Role.EMPLOYEE)
            .active(true)
            .build(); // sin passwordHash — exactamente como CompanyAdminEmployeeService.create()
        userRepo.save(whitelisted);

        FirstLoginRequest req = new FirstLoginRequest(email, "Juan", "Pérez", "clave-nueva-123");
        AuthService.AuthResult result = authService.firstLogin(req);

        assertThat(result.accessToken()).isNotBlank();

        User activated = userRepo.findByEmail(email).orElseThrow();
        assertThat(activated.getPasswordHash()).isNotNull();
        assertThat(activated.getFirstLoginAt()).isNotNull();
        assertThat(activated.getFirstName()).isEqualTo("Juan");
    }

    @Test
    void firstLoginRechazaUnaCuentaQueYaTienePasswordIgualQueAntes() {
        String email = "b2b-first-login-ya-activa-" + System.nanoTime() + "@test.arias.com";
        User alreadyActive = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode("clave-vieja"))
            .role(Role.EMPLOYEE)
            .active(true)
            .build();
        userRepo.save(alreadyActive);

        FirstLoginRequest req = new FirstLoginRequest(email, "Juan", "Pérez", "clave-nueva-123");

        assertThatThrownBy(() -> authService.firstLogin(req))
            .isInstanceOf(InvalidCredentialsException.class);
    }
}
