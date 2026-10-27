package com.arias.auth;

import com.arias.auth.dto.CompleteProfileRequest;
import com.arias.auth.dto.FirstLoginRequest;
import com.arias.auth.dto.MeResponse;
import com.arias.common.exception.BusinessException;
import com.arias.common.exception.InvalidCredentialsException;
import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

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

    @Autowired
    private CompanyRepository companyRepo;

    @Autowired
    private CategoryRepository categoryRepo;

    private Company persistCompany() {
        Category category = categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime())
            .ordenDisplay(0)
            .enabled(true)
            .creditCost(1)
            .build());
        return companyRepo.save(Company.builder()
            .nombre("Empresa-" + System.nanoTime())
            .cuit(String.valueOf(20000000000L + (System.nanoTime() % 9000000000L)))
            .calle("Calle Falsa")
            .altura("123")
            .horaEntrega(LocalTime.of(13, 0))
            .categoriaDefault(category)
            .enabled(true)
            .build());
    }

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
    @DisplayName("me(): returns the nickname and the displayName with the kitchen fallback")
    void meDevuelveApodoYDisplayNameConElFallbackDeCocina() {
        User conApodo = saveUser("Coty", "Ana", "Perez");
        User sinApodo = saveUser(null, "Ana", "Perez");
        User sinNombre = saveUser(null, null, null);

        MeResponse a = authService.me(conApodo.getId());
        assertThat(a.nickname()).isEqualTo("Coty");
        assertThat(a.displayName()).isEqualTo("Coty");

        MeResponse b = authService.me(sinApodo.getId());
        assertThat(b.nickname()).isNull();
        assertThat(b.displayName()).isEqualTo("Ana Perez");

        MeResponse c = authService.me(sinNombre.getId());
        assertThat(c.displayName()).isEqualTo(sinNombre.getEmail());
    }

    @Test
    @DisplayName("me(): an admin without phone or nickname is not sent to complete-profile")
    void meNoPideCompletarPerfilAUnAdmin() {
        User admin = userRepo.save(User.builder()
            .email("admin-" + System.nanoTime() + "@test.arias.com")
            .passwordHash(passwordEncoder.encode("clave-segura-123"))
            .role(Role.SUPER_ADMIN)
            .active(true)
            .build());

        assertThat(authService.me(admin.getId()).profileComplete()).isTrue();
    }

    @Test
    @DisplayName("me(): a B2C customer without phone or nickname must complete the profile")
    void mePideCompletarPerfilAUnB2cIncompleto() {
        User b2c = saveUser(null, "Ana", "Perez");

        assertThat(authService.me(b2c.getId()).profileComplete()).isFalse();
    }

    @Test
    void completeProfileGuardaElCelularDeDiezDigitosConPrefijo549() {
        User user = saveUser(null, "Ana", "Perez");

        authService.completeProfile(user.getId(), new CompleteProfileRequest("11 5987-6547", "Coty"));

        User updated = userRepo.findById(user.getId()).orElseThrow();
        assertThat(updated.getPhone()).isEqualTo("+5491159876547");
        assertThat(updated.getNickname()).isEqualTo("Coty");
    }

    @Test
    void completeProfileRechazaUnCelularConLetras() {
        User user = saveUser(null, "Ana", "Perez");

        assertThatThrownBy(() -> authService.completeProfile(
                user.getId(), new CompleteProfileRequest("11abc654", "Coty")))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("INVALID_PHONE");
                assertThat(ex.getMessage()).isEqualTo("Ingresá tu celular, solo números");
            });
    }

    @Test
    void completeProfileRechazaUnCelularYaAsociadoAOtraCuenta() {
        User first = saveUser(null, "Ana", "Perez");
        User second = saveUser(null, "Beto", "Gomez");
        authService.completeProfile(first.getId(), new CompleteProfileRequest("1159876547", "Ana"));

        assertThatThrownBy(() -> authService.completeProfile(
                second.getId(), new CompleteProfileRequest("1159876547", "Beto")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PHONE_ALREADY_REGISTERED"));
    }

    private User saveUser(String nickname, String firstName, String lastName) {
        return userRepo.save(User.builder()
            .email("me-" + System.nanoTime() + "@test.arias.com")
            .passwordHash(passwordEncoder.encode("clave-segura-123"))
            .role(Role.EMPLOYEE)
            .active(true)
            .nickname(nickname)
            .firstName(firstName)
            .lastName(lastName)
            .build());
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
            .company(persistCompany())
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

    @Test
    void firstLoginRejectsAGoogleCreatedB2cAccountAndLeavesItPasswordless() {
        // B2C accounts created through Google have no company and no password.
        // Anyone who knows the email must not be able to claim them.
        String email = "b2c-google-first-login-" + System.nanoTime() + "@test.arias.com";
        userRepo.save(User.builder()
            .email(email)
            .role(Role.EMPLOYEE)
            .active(true)
            .googleSub("sub-" + System.nanoTime())
            .emailVerifiedAt(java.time.Instant.now())
            .build());

        FirstLoginRequest req = new FirstLoginRequest(email, "Evil", "Actor", "attacker-password-1");

        assertThatThrownBy(() -> authService.firstLogin(req))
            .isInstanceOf(InvalidCredentialsException.class);
        assertThat(userRepo.findByEmail(email).orElseThrow().getPasswordHash()).isNull();
    }

    @Test
    void checkEmailOnlyFlagsCompanyAccountsAsFirstLoginPending() {
        String b2cEmail = "b2c-check-email-" + System.nanoTime() + "@test.arias.com";
        userRepo.save(User.builder().email(b2cEmail).role(Role.EMPLOYEE).active(true)
            .googleSub("sub-" + System.nanoTime()).build());
        String companyEmail = "b2b-check-email-" + System.nanoTime() + "@test.arias.com";
        userRepo.save(User.builder().email(companyEmail).role(Role.EMPLOYEE).company(persistCompany())
            .active(true).build());

        assertThat(authService.checkEmail(b2cEmail).requiresFirstLogin()).isFalse();
        assertThat(authService.checkEmail(companyEmail).requiresFirstLogin()).isTrue();
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + System.nanoTime() + "@test.arias.com";
    }

    private User.UserBuilder baseUser(String email) {
        return User.builder().email(email).role(Role.EMPLOYEE).active(true);
    }

    @Test
    void checkEmailStatusIsNotFoundForUnknownEmail() {
        var res = authService.checkEmail(uniqueEmail("unknown"));
        assertThat(res.status()).isEqualTo(AccountStatus.NOT_FOUND);
        assertThat(res.requiresFirstLogin()).isFalse();
    }

    @Test
    void checkEmailStatusIsNotFoundForSoftDeletedUser() {
        String email = uniqueEmail("deleted");
        userRepo.save(baseUser(email).passwordHash(passwordEncoder.encode("pw-123456"))
            .deletedAt(java.time.Instant.now()).build());
        assertThat(authService.checkEmail(email).status()).isEqualTo(AccountStatus.NOT_FOUND);
    }

    @Test
    void checkEmailStatusIsPasswordForInactiveUser() {
        String email = uniqueEmail("inactive");
        userRepo.save(User.builder().email(email).role(Role.EMPLOYEE).active(false)
            .company(persistCompany()).build());
        var res = authService.checkEmail(email);
        assertThat(res.status()).isEqualTo(AccountStatus.PASSWORD);
        assertThat(res.requiresFirstLogin()).isFalse();
    }

    @Test
    void checkEmailStatusIsPasswordForB2cWithPassword() {
        String email = uniqueEmail("pw");
        userRepo.save(baseUser(email).passwordHash(passwordEncoder.encode("pw-123456")).build());
        assertThat(authService.checkEmail(email).status()).isEqualTo(AccountStatus.PASSWORD);
    }

    @Test
    void checkEmailStatusIsGoogleForB2cGoogleOnly() {
        String email = uniqueEmail("google");
        userRepo.save(baseUser(email).googleSub("sub-" + System.nanoTime()).build());
        var res = authService.checkEmail(email);
        assertThat(res.status()).isEqualTo(AccountStatus.GOOGLE);
        assertThat(res.requiresFirstLogin()).isFalse();
    }

    @Test
    void checkEmailStatusIsPasswordWhenGoogleAccountAlsoHasPassword() {
        String email = uniqueEmail("both");
        userRepo.save(baseUser(email).googleSub("sub-" + System.nanoTime())
            .passwordHash(passwordEncoder.encode("pw-123456")).build());
        assertThat(authService.checkEmail(email).status()).isEqualTo(AccountStatus.PASSWORD);
    }

    @Test
    void checkEmailStatusIsPasswordForUnverifiedSelfRegistered() {
        String email = uniqueEmail("unverified");
        userRepo.save(baseUser(email).passwordHash(passwordEncoder.encode("pw-123456"))
            .emailVerifiedAt(null).build());
        assertThat(authService.checkEmail(email).status()).isEqualTo(AccountStatus.PASSWORD);
    }

    @Test
    void checkEmailStatusIsFirstLoginForWhitelistedCompanyWithoutPassword() {
        String email = uniqueEmail("whitelisted");
        userRepo.save(baseUser(email).company(persistCompany()).build());
        var res = authService.checkEmail(email);
        assertThat(res.status()).isEqualTo(AccountStatus.FIRST_LOGIN);
        assertThat(res.requiresFirstLogin()).isTrue();
    }

    @Test
    void checkEmailNormalizesCaseAndWhitespaceLikeLogin() {
        String email = uniqueEmail("case");
        userRepo.save(baseUser(email).googleSub("sub-" + System.nanoTime()).build());
        assertThat(authService.checkEmail("  " + email.toUpperCase() + " ").status())
            .isEqualTo(AccountStatus.GOOGLE);
    }
}
