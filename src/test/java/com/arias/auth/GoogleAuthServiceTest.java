package com.arias.auth;

import com.arias.common.exception.InvalidCredentialsException;
import com.arias.credits.CreditLedgerService;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.MovementType;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.json.webtoken.JsonWebSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.security.GeneralSecurityException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Login/alta con Google — spec {@code self-registration} (dominio/API,
 * unidad 5). {@link GoogleIdTokenVerifier} queda mockeado (harness de la
 * tabla de unidades de trabajo) para no depender de la red de Google ni de
 * un client id real; cada test construye su propio {@code GoogleIdToken}
 * "válido" a mano.
 *
 * <p>{@code GoogleIdToken} declara {@code getPayload()} tres veces con tipos
 * de retorno covariantes (bridge methods) — mockearlo directo con Mockito
 * confunde el registro de stubbing ("Unfinished stubbing"). Por eso los
 * tokens de prueba son instancias REALES de {@code GoogleIdToken} (su
 * constructor público no valida nada — la validación de firma vive en
 * {@code GoogleIdTokenVerifier.verify}, que es lo que este test mockea).
 */
@SpringBootTest
@Transactional
class GoogleAuthServiceTest {

    @Autowired
    private GoogleAuthService googleAuthService;

    @Autowired
    private CreditLedgerService creditLedgerService;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private CreditWalletRepository walletRepo;

    @Autowired
    private CreditMovementRepository movementRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private GoogleIdTokenVerifier verifier;

    private GoogleIdToken validToken(String sub, String email, boolean emailVerified,
                                      String givenName, String familyName) {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject(sub);
        payload.setEmail(email);
        payload.setEmailVerified(emailVerified);
        if (givenName != null) payload.set("given_name", givenName);
        if (familyName != null) payload.set("family_name", familyName);

        // Instancia real — no un mock — ver el javadoc de la clase.
        return new GoogleIdToken(new JsonWebSignature.Header(), payload, new byte[0], new byte[0]);
    }

    private long welcomeGrantCount(Long userId) {
        return movementRepo.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .filter(m -> m.getType() == MovementType.WELCOME_GRANT)
            .count();
    }

    @Test
    void altaConGoogleCreaCuentaVerificadaYOtorgaElAlmuerzoDeBienvenida() throws Exception {
        String email = "google-alta-" + System.nanoTime() + "@test.arias.com";
        String idTokenValue = "raw-token-" + System.nanoTime();
        when(verifier.verify(idTokenValue))
            .thenReturn(validToken("sub-" + System.nanoTime(), email, true, "Ana", "Gómez"));

        AuthService.AuthResult result = googleAuthService.loginWithGoogle(idTokenValue);

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshTokenValue()).isNotBlank();

        User created = userRepo.findByEmail(email).orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(created.getGoogleSub()).isNotBlank();
        assertThat(created.getEmailVerifiedAt()).isNotNull();
        assertThat(created.getPasswordHash()).isNull();
        assertThat(created.getFirstName()).isEqualTo("Ana");
        assertThat(created.getLastName()).isEqualTo("Gómez");

        CreditWallet wallet = walletRepo.findById(created.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(1);
        assertThat(welcomeGrantCount(created.getId())).isEqualTo(1);
    }

    @Test
    void loginConGoogleVinculaCuentaExistenteSinVerificarYOtorgaElAlmuerzo() throws Exception {
        // Cuenta autorregistrada (unidad 4) que nunca verificó el correo —
        // llega a Google login sin google_sub y sin email_verified_at.
        String email = "google-vincula-sin-verificar-" + System.nanoTime() + "@test.arias.com";
        User unverified = userRepo.save(User.builder()
            .email(email)
            .firstName("Bruno")
            .phone("+549" + (1122330100L + System.nanoTime() % 1000))
            .nickname("Bru")
            .role(Role.EMPLOYEE)
            .active(true)
            .build());
        assertThat(unverified.getGoogleSub()).isNull();
        assertThat(unverified.getEmailVerifiedAt()).isNull();

        String idTokenValue = "raw-token-" + System.nanoTime();
        when(verifier.verify(idTokenValue))
            .thenReturn(validToken("sub-" + System.nanoTime(), email, true, "Bruno", null));

        googleAuthService.loginWithGoogle(idTokenValue);

        User linked = userRepo.findByEmail(email).orElseThrow();
        assertThat(linked.getGoogleSub()).isNotBlank();
        assertThat(linked.getEmailVerifiedAt()).isNotNull(); // primera validación — vía Google

        assertThat(welcomeGrantCount(linked.getId())).isEqualTo(1);
    }

    @Test
    void loginConGoogleVinculaEmpleadoDeEmpresaYaVerificadoSinOtorgarSegundoAlmuerzo() throws Exception {
        // Empleado de empresa: first-login ya hecho (tiene password_hash),
        // email_verified_at ya seteado por la migración V16 — Google login
        // NO debe re-otorgar el almuerzo, spec "Sin doble otorgamiento".
        String email = "google-empleado-" + System.nanoTime() + "@test.arias.com";
        User employee = userRepo.save(User.builder()
            .email(email)
            .firstName("Carla")
            .lastName("Diaz")
            .passwordHash(passwordEncoder.encode("password123"))
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .build());

        String idTokenValue = "raw-token-" + System.nanoTime();
        when(verifier.verify(idTokenValue))
            .thenReturn(validToken("sub-" + System.nanoTime(), email, true, "Carla", "Diaz"));

        googleAuthService.loginWithGoogle(idTokenValue);

        User linked = userRepo.findByEmail(email).orElseThrow();
        assertThat(linked.getGoogleSub()).isNotBlank();
        assertThat(linked.getPasswordHash()).isNotBlank(); // password existente sigue funcionando

        assertThat(welcomeGrantCount(linked.getId())).isEqualTo(0);
        assertThat(walletRepo.findById(employee.getId())).isEmpty();
    }

    @Test
    void loginConGoogleRepetidoConMismoGoogleSubNoOtorgaSegundoAlmuerzo() throws Exception {
        String email = "google-repetido-" + System.nanoTime() + "@test.arias.com";
        String sub = "sub-" + System.nanoTime();

        when(verifier.verify("primer-token"))
            .thenReturn(validToken(sub, email, true, "Dana", null));
        googleAuthService.loginWithGoogle("primer-token");

        User user = userRepo.findByEmail(email).orElseThrow();
        assertThat(welcomeGrantCount(user.getId())).isEqualTo(1);

        when(verifier.verify("segundo-token"))
            .thenReturn(validToken(sub, email, true, "Dana", null));
        AuthService.AuthResult secondLogin = googleAuthService.loginWithGoogle("segundo-token");

        assertThat(secondLogin.accessToken()).isNotBlank();
        assertThat(welcomeGrantCount(user.getId())).isEqualTo(1); // sigue siendo 1, no 2
    }

    @Test
    void loginConGoogleRechazaSiElGoogleSubYaEstaVinculadoAOtraCuenta() throws Exception {
        String email = "google-sub-distinto-" + System.nanoTime() + "@test.arias.com";
        User existing = userRepo.save(User.builder()
            .email(email)
            .firstName("Elena")
            .role(Role.EMPLOYEE)
            .active(true)
            .googleSub("sub-original-" + System.nanoTime())
            .emailVerifiedAt(Instant.now())
            .build());

        String idTokenValue = "raw-token-" + System.nanoTime();
        when(verifier.verify(idTokenValue))
            .thenReturn(validToken("sub-impostor-" + System.nanoTime(), email, true, "Elena", null));

        assertThatThrownBy(() -> googleAuthService.loginWithGoogle(idTokenValue))
            .isInstanceOf(InvalidCredentialsException.class);

        User unchanged = userRepo.findByEmail(email).orElseThrow();
        assertThat(unchanged.getGoogleSub()).isEqualTo(existing.getGoogleSub());
        assertThat(welcomeGrantCount(unchanged.getId())).isEqualTo(0);
    }

    @Test
    void loginConGoogleRechazaEmailNoVerificadoPorGoogle() throws Exception {
        String email = "google-sin-verificar-" + System.nanoTime() + "@test.arias.com";
        String idTokenValue = "raw-token-" + System.nanoTime();
        when(verifier.verify(idTokenValue))
            .thenReturn(validToken("sub-" + System.nanoTime(), email, false, "Fede", null));

        assertThatThrownBy(() -> googleAuthService.loginWithGoogle(idTokenValue))
            .isInstanceOf(InvalidCredentialsException.class);

        assertThat(userRepo.findByEmail(email)).isEmpty();
    }

    @Test
    void loginConGoogleRechazaTokenInvalido() throws Exception {
        when(verifier.verify(anyString())).thenReturn(null);

        assertThatThrownBy(() -> googleAuthService.loginWithGoogle("token-invalido"))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginConGoogleRechazaTokenExpiradoOAudienciaIncorrecta() throws Exception {
        when(verifier.verify(eq("token-expirado")))
            .thenThrow(new GeneralSecurityException("token expirado o audiencia inválida"));

        assertThatThrownBy(() -> googleAuthService.loginWithGoogle("token-expirado"))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void grantWelcomeLunchEsIdempotenteAnteUnSegundoIntentoDirectoYNoRompeLaRequest() {
        String email = "grant-directo-" + System.nanoTime() + "@test.arias.com";
        User user = userRepo.save(User.builder()
            .email(email)
            .firstName("Gina")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .build());

        creditLedgerService.grantWelcomeLunch(user.getId());
        // Segundo intento: no debe tirar ni crear un segundo movimiento —
        // el pre-check de existsByUserIdAndType evita tocar el índice único.
        creditLedgerService.grantWelcomeLunch(user.getId());

        assertThat(welcomeGrantCount(user.getId())).isEqualTo(1);
        assertThat(walletRepo.findById(user.getId()).orElseThrow().getAvailable()).isEqualTo(1);
    }
}
