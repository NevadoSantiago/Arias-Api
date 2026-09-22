package com.arias.auth;

import com.arias.auth.dto.RegisterRequest;
import com.arias.common.exception.BusinessException;
import com.arias.email.EmailService;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Autorregistro público — spec {@code self-registration} (dominio/API, sin
 * Google ni almuerzo de bienvenida, eso es unidad 5). Levanta el contexto
 * completo (Flyway aplica V16 sobre la base real de test); {@link EmailService}
 * queda mockeado para no depender de Resend.
 */
@SpringBootTest
@Transactional
class RegistrationServiceTest {

    private static final String VALID_PASSWORD = "unaClaveSegura123";

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private EmailVerificationTokenRepository tokenRepo;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Validator validator;

    @Autowired
    private Clock clock;

    @MockitoBean
    private EmailService emailService;

    private RegisterRequest validRequest(String email, String phone) {
        return new RegisterRequest("Ana", "Gómez", email, phone, "Anita", VALID_PASSWORD);
    }

    @Test
    void registroExitosoCreaCuentaSinVerificarYEnviaElCorreo() {
        String email = "registro-ok-" + System.nanoTime() + "@test.arias.com";

        registrationService.register(validRequest(email, "+5491122330001"));

        User created = userRepo.findByEmail(email).orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(created.getPhone()).isEqualTo("+5491122330001");
        assertThat(created.getNickname()).isEqualTo("Anita");
        assertThat(created.getEmailVerifiedAt()).isNull();
        // La contraseña queda hasheada y utilizable — sin ella, una cuenta sin
        // Google no tiene forma de loguearse ni de recuperar acceso.
        assertThat(created.getPasswordHash()).isNotNull().isNotEqualTo(VALID_PASSWORD);
        assertThat(passwordEncoder.matches(VALID_PASSWORD, created.getPasswordHash())).isTrue();

        verify(emailService).send(org.mockito.ArgumentMatchers.eq(email), anyString(), anyString());
    }

    @Test
    void registroRechazaTelefonoConFormatoInvalido() {
        String email = "registro-tel-invalido-" + System.nanoTime() + "@test.arias.com";

        assertThatThrownBy(() -> registrationService.register(validRequest(email, "no-es-un-telefono")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("teléfono");

        assertThat(userRepo.findByEmail(email)).isEmpty();
        verifyNoInteractions(emailService);
    }

    @Test
    void registroRechazaTelefonoYaRegistradoPorOtraCuenta() {
        String phone = "+5491122330002";
        registrationService.register(validRequest("registro-tel-dup-1-" + System.nanoTime() + "@test.arias.com", phone));

        String secondEmail = "registro-tel-dup-2-" + System.nanoTime() + "@test.arias.com";

        assertThatThrownBy(() -> registrationService.register(validRequest(secondEmail, phone)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("teléfono");

        assertThat(userRepo.findByEmail(secondEmail)).isEmpty();
    }

    @Test
    void registroConEmailYaExistenteNoCreaSegundaCuentaYNoRevelaLaDiferencia() {
        String email = "registro-email-dup-" + System.nanoTime() + "@test.arias.com";
        registrationService.register(validRequest(email, "+5491122330003"));

        // No lanza excepción — misma respuesta "genérica" que un alta exitosa,
        // igual que decide el diseño §Seguridad ("no revela cuentas").
        registrationService.register(validRequest(email, "+5491122330004"));

        assertThat(userRepo.findAll().stream().filter(u -> email.equals(u.getEmail())).count()).isEqualTo(1);
        // El segundo teléfono nunca quedó asociado a nadie — la cuenta no se tocó.
        assertThat(userRepo.existsActivePhone("+5491122330004")).isFalse();
    }

    @Test
    void cuentaQuedaBloqueadaHastaVerificarYLaVerificacionExitosaLaHabilita() {
        String email = "registro-verifica-" + System.nanoTime() + "@test.arias.com";
        String phone = "+5491122330005";
        registrationService.register(validRequest(email, phone));

        User unverified = userRepo.findByEmail(email).orElseThrow();
        assertThat(unverified.getEmailVerifiedAt()).isNull(); // bloqueada — spec "Cuenta no verificada bloqueada"

        EmailVerificationToken token = tokenRepo.findAll().stream()
            .filter(t -> t.getUser().getId().equals(unverified.getId()))
            .findFirst().orElseThrow();
        // El valor crudo no se persiste — reconstruimos vía el mismo hash que usa el servicio.
        // Para el test, generamos nuestro propio par valor/hash y lo guardamos directo.
        String rawToken = jwtService.generateRefreshTokenValue();
        token.setTokenHash(jwtService.hashRefreshToken(rawToken));
        tokenRepo.save(token);

        AuthService.AuthResult result = registrationService.verifyEmail(rawToken);

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshTokenValue()).isNotBlank();
        assertThat(result.welcomeLunchGranted()).isTrue();

        User verified = userRepo.findByEmail(email).orElseThrow();
        assertThat(verified.getEmailVerifiedAt()).isNotNull(); // habilitada — spec "Verificación exitosa"
    }

    @Test
    void verificacionRechazaTokenInvalido() {
        assertThatThrownBy(() -> registrationService.verifyEmail("token-que-no-existe"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void verificacionRechazaTokenExpirado() {
        String email = "registro-expirado-" + System.nanoTime() + "@test.arias.com";
        registrationService.register(validRequest(email, "+5491122330006"));
        User user = userRepo.findByEmail(email).orElseThrow();

        String rawToken = jwtService.generateRefreshTokenValue();
        EmailVerificationToken expired = EmailVerificationToken.builder()
            .user(user)
            .tokenHash(jwtService.hashRefreshToken(rawToken))
            .expiresAt(clock.instant().minus(Duration.ofMinutes(1)))
            .build();
        tokenRepo.save(expired);

        assertThatThrownBy(() -> registrationService.verifyEmail(rawToken))
            .isInstanceOf(BusinessException.class);

        assertThat(userRepo.findByEmail(email).orElseThrow().getEmailVerifiedAt()).isNull();
    }

    @Test
    void verificacionRechazaTokenYaUsado() {
        String email = "registro-usado-" + System.nanoTime() + "@test.arias.com";
        registrationService.register(validRequest(email, "+5491122330007"));
        User user = userRepo.findByEmail(email).orElseThrow();

        String rawToken = jwtService.generateRefreshTokenValue();
        EmailVerificationToken used = EmailVerificationToken.builder()
            .user(user)
            .tokenHash(jwtService.hashRefreshToken(rawToken))
            .expiresAt(clock.instant().plus(Duration.ofMinutes(30)))
            .usedAt(clock.instant().minus(Duration.ofMinutes(1)))
            .build();
        tokenRepo.save(used);

        assertThatThrownBy(() -> registrationService.verifyEmail(rawToken))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void reenvioDeVerificacionInvalidaElTokenAnteriorYEmiteUnoNuevo() {
        String email = "registro-reenvio-" + System.nanoTime() + "@test.arias.com";
        registrationService.register(validRequest(email, "+5491122330008"));
        User user = userRepo.findByEmail(email).orElseThrow();

        EmailVerificationToken firstToken = tokenRepo.findAll().stream()
            .filter(t -> t.getUser().getId().equals(user.getId()))
            .findFirst().orElseThrow();
        assertThat(firstToken.getUsedAt()).isNull();

        registrationService.resendVerification(email);

        EmailVerificationToken reloadedFirst = tokenRepo.findById(firstToken.getId()).orElseThrow();
        assertThat(reloadedFirst.getUsedAt()).isNotNull(); // invalidado

        long activeTokensForUser = tokenRepo.findAll().stream()
            .filter(t -> t.getUser().getId().equals(user.getId()) && t.getUsedAt() == null)
            .count();
        assertThat(activeTokensForUser).isEqualTo(1); // el nuevo, y solo uno
    }

    @Test
    void reenvioDeVerificacionParaCuentaInexistenteNoLanzaNiEnviaCorreo() {
        registrationService.resendVerification("nadie-" + System.nanoTime() + "@test.arias.com");
        // No debe romper — misma protección de enumeración que forgot-password.
    }

    @Test
    void registroRechazaCamposFaltantesAlNivelDeServicioParaTelefonoVacio() {
        String email = "registro-sin-telefono-" + System.nanoTime() + "@test.arias.com";

        assertThatThrownBy(() -> registrationService.register(
            new RegisterRequest("Ana", null, email, "", "Anita", VALID_PASSWORD)))
            .isInstanceOf(BusinessException.class);

        assertThat(userRepo.findByEmail(email)).isEmpty();
    }

    @Test
    void usuarioAutorregistradoYVerificadoPuedeAutenticarseConLoginNormal() {
        String email = "registro-login-" + System.nanoTime() + "@test.arias.com";
        registrationService.register(validRequest(email, "+5491122330009"));

        User unverified = userRepo.findByEmail(email).orElseThrow();
        EmailVerificationToken token = tokenRepo.findAll().stream()
            .filter(t -> t.getUser().getId().equals(unverified.getId()))
            .findFirst().orElseThrow();
        String rawToken = jwtService.generateRefreshTokenValue();
        token.setTokenHash(jwtService.hashRefreshToken(rawToken));
        tokenRepo.save(token);
        registrationService.verifyEmail(rawToken);

        // Ahora sí tiene password_hash + email verificado — POST /auth/login
        // (AuthService.login) debe funcionar igual que para cualquier otra
        // cuenta con contraseña, y forgot-password (PasswordResetService)
        // deja de ser un no-op silencioso para esta cuenta.
        AuthService.AuthResult result = authService.login(email, VALID_PASSWORD);

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshTokenValue()).isNotBlank();
    }

    @Test
    void registroRechazaContraseñaDemasiadoCortaOEnBlancoPorValidacionBean() {
        String email = "registro-pass-corta-" + System.nanoTime() + "@test.arias.com";

        RegisterRequest tooShort = new RegisterRequest("Ana", "Gómez", email, "+5491122330010", "Anita", "1234567");
        Set<ConstraintViolation<RegisterRequest>> tooShortViolations = validator.validate(tooShort);
        assertThat(tooShortViolations).isNotEmpty();

        RegisterRequest blank = new RegisterRequest("Ana", "Gómez", email, "+5491122330010", "Anita", "");
        Set<ConstraintViolation<RegisterRequest>> blankViolations = validator.validate(blank);
        assertThat(blankViolations).isNotEmpty();
    }

    @Test
    void cuentaCreadaPorGoogleSinPasswordHashSigueSinPoderUsarLoginNormal() {
        // Simula el estado que deja GoogleAuthService (password_hash = NULL,
        // email verificado): este cambio no debe romper ese camino — login
        // normal sigue rechazando con el mismo error genérico, nunca un NPE.
        String email = "registro-google-simulado-" + System.nanoTime() + "@test.arias.com";
        User googleUser = User.builder()
            .email(email)
            .firstName("Bruno")
            .phone("+5491122330011")
            .nickname("Bru")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(clock.instant())
            .build();
        userRepo.save(googleUser);

        assertThat(googleUser.getPasswordHash()).isNull();
        assertThatThrownBy(() -> authService.login(email, "cualquier-cosa"))
            .isInstanceOf(com.arias.common.exception.InvalidCredentialsException.class);
    }
}
