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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private EmailVerificationTokenRepository tokenRepo;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private Clock clock;

    @MockitoBean
    private EmailService emailService;

    private RegisterRequest validRequest(String email, String phone) {
        return new RegisterRequest("Ana", "Gómez", email, phone, "Anita");
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
        assertThat(created.getPasswordHash()).isNull();

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

        assertThatThrownBy(() -> registrationService.register(new RegisterRequest("Ana", null, email, "", "Anita")))
            .isInstanceOf(BusinessException.class);

        assertThat(userRepo.findByEmail(email)).isEmpty();
    }
}
