package com.arias.credits;

import com.arias.auth.AuthService;
import com.arias.auth.EmailVerificationToken;
import com.arias.auth.EmailVerificationTokenRepository;
import com.arias.auth.JwtService;
import com.arias.auth.RegistrationService;
import com.arias.auth.dto.RegisterRequest;
import com.arias.email.EmailService;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** With {@code arias.credits.welcome-grant-enabled=false} no account gets the welcome lunch. */
@SpringBootTest(properties = "arias.credits.welcome-grant-enabled=false")
@Transactional
class WelcomeGrantDisabledTest {

    @Autowired
    private CreditLedgerService ledgerService;

    @Autowired
    private CreditMovementRepository movementRepo;

    @Autowired
    private CreditWalletRepository walletRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private EmailVerificationTokenRepository tokenRepo;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private EmailService emailService;

    @Test
    @DisplayName("grantWelcomeLunch returns false and writes nothing when the switch is off")
    void grantWelcomeLunchDoesNothingWhenSwitchedOff() {
        User user = userRepo.save(User.builder()
            .email("welcome-off-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .build());

        boolean granted = ledgerService.grantWelcomeLunch(user.getId());

        assertThat(granted).isFalse();
        assertThat(movementRepo.existsByUserIdAndType(user.getId(), MovementType.WELCOME_GRANT)).isFalse();
        assertThat(walletRepo.findById(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("verifying the email with the switch off reports no welcome lunch and leaves 0 lunches")
    void verifyEmailGrantsNothingWhenSwitchedOff() {
        String email = "welcome-off-verify-" + System.nanoTime() + "@test.arias.com";
        registrationService.register(new RegisterRequest("Ana", "Gómez", email, "1122330099", "Anita", "unaClaveSegura123"));
        User user = userRepo.findByEmail(email).orElseThrow();
        EmailVerificationToken token = tokenRepo.findAll().stream()
            .filter(t -> t.getUser().getId().equals(user.getId()))
            .findFirst().orElseThrow();
        String rawToken = jwtService.generateRefreshTokenValue();
        token.setTokenHash(jwtService.hashRefreshToken(rawToken));
        tokenRepo.save(token);

        AuthService.AuthResult result = registrationService.verifyEmail(rawToken);

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.welcomeLunchGranted()).isFalse();
        assertThat(userRepo.findByEmail(email).orElseThrow().getEmailVerifiedAt()).isNotNull();
        assertThat(movementRepo.existsByUserIdAndType(user.getId(), MovementType.WELCOME_GRANT)).isFalse();
    }
}
