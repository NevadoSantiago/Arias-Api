package com.arias.auth;

import com.arias.auth.dto.RegisterRequest;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditLedgerService;
import com.arias.email.EmailVerificationEmails;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Autorregistro público — spec {@code self-registration} (requisitos de
 * dominio/API). Requiere password: sin ella, un usuario sin Google que
 * pierde el correo de verificación queda permanentemente bloqueado — no hay
 * password para "forgot-password" y no puede autenticarse (diseño §Decisión
 * 10). La cuenta igual queda inactiva para uso normal hasta la verificación
 * de correo (spec "Verificación obligatoria de correo electrónico"). {@link
 * #verifyEmail} sigue siendo el único punto que emite sesión en el flujo de
 * registro — poseer el token del mail es la prueba de que el correo es
 * propio; emitir tokens en {@link #register} sería una falla de
 * account-takeover (cualquiera podría "loguearse" registrando el email de
 * otra persona).
 *
 * <p>El almuerzo de bienvenida (spec "Otorgamiento único") se otorga en
 * {@link #verifyEmail}, la primera vez que deja {@code emailVerifiedAt}
 * seteado — {@link CreditLedgerService#grantWelcomeLunch} es la misma
 * llamada que dispara el login con Google (unidad 5) cuando ES Google quien
 * deja el correo verificado por primera vez.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    /** TTL del link de verificación — más largo que el reset de password (15 min) porque no es tan sensible. */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final UserRepository userRepo;
    private final EmailVerificationTokenRepository tokenRepo;
    private final JwtService jwtService;
    private final AuthService authService;
    private final EmailVerificationEmails emails;
    private final CreditLedgerService creditLedgerService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * Alta pública (spec "Alta pública con datos mínimos"). La respuesta al
     * caller debe ser SIEMPRE la misma exista o no el email — spec "Coexistencia..."
     * y diseño §Seguridad ("no revela cuentas, igual que check-email"); el
     * único dato que SÍ se revela es el teléfono duplicado (spec "Validación
     * de teléfono contra duplicados").
     */
    @Transactional
    public void register(RegisterRequest req) {
        String phone = PhoneNumbers.normalizeE164(req.phone())
            .orElseThrow(() -> BusinessException.badRequest(
                "INVALID_PHONE", "El teléfono no tiene un formato válido"));

        if (userRepo.existsActivePhone(phone)) {
            throw BusinessException.conflict(
                "PHONE_ALREADY_REGISTERED", "Ese teléfono ya está asociado a una cuenta");
        }

        String email = normalize(req.email());
        if (userRepo.findByEmail(email).isPresent()) {
            // No revela existencia de la cuenta — mismo trato que forgot-password:
            // no se crea nada, no se manda mail, pero tampoco se lanza error.
            return;
        }

        User user = User.builder()
            .email(email)
            .firstName(req.firstName())
            .lastName(req.lastName())
            .phone(phone)
            .nickname(req.nickname())
            .passwordHash(passwordEncoder.encode(req.password()))
            .role(Role.EMPLOYEE)
            .active(true)
            .build();
        userRepo.save(user);

        issueVerificationToken(user);
    }

    /**
     * Confirma el correo (spec "Verificación exitosa") y, con eso, emite
     * sesión — mismo mecanismo que login/first-login ({@link
     * AuthService#issueTokens}). Otorga el almuerzo de bienvenida SOLO la
     * primera vez que esta llamada deja {@code emailVerifiedAt} seteado
     * (spec "Otorgamiento único" / "Sin doble otorgamiento") — un
     * reenvío/reverificación posterior entra al {@code if} en falso y ni
     * siquiera intenta el otorgamiento.
     */
    @Transactional
    public AuthService.AuthResult verifyEmail(String tokenValue) {
        String tokenHash = jwtService.hashRefreshToken(tokenValue);

        EmailVerificationToken token = tokenRepo.findByTokenHash(tokenHash)
            .orElseThrow(RegistrationService::invalidTokenException);

        Instant now = clock.instant();
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(now)) {
            throw invalidTokenException();
        }

        token.setUsedAt(now);
        tokenRepo.save(token);

        User user = token.getUser();
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now);
            userRepo.save(user);
            creditLedgerService.grantWelcomeLunch(user.getId());
        }

        return authService.issueTokens(user);
    }

    /**
     * Reenvío del correo de verificación (frontend UX de "reenviar"). Misma
     * protección de enumeración de cuentas que {@link #register}: nunca
     * lanza, solo actúa si la cuenta existe y sigue sin verificar.
     */
    @Transactional
    public void resendVerification(String rawEmail) {
        String email = normalize(rawEmail);
        userRepo.findByEmail(email).ifPresent(user -> {
            if (user.getEmailVerifiedAt() == null) {
                issueVerificationToken(user);
            }
        });
    }

    private void issueVerificationToken(User user) {
        Instant now = clock.instant();
        tokenRepo.invalidateAllForUser(user.getId(), now);

        String tokenValue = jwtService.generateRefreshTokenValue();
        String tokenHash = jwtService.hashRefreshToken(tokenValue);

        EmailVerificationToken token = EmailVerificationToken.builder()
            .user(user)
            .tokenHash(tokenHash)
            .expiresAt(now.plus(TOKEN_TTL))
            .build();
        tokenRepo.save(token);

        emails.sendVerificationLink(user.getEmail(), user.getFirstName(), tokenValue);
    }

    private static BusinessException invalidTokenException() {
        return BusinessException.badRequest(
            "INVALID_VERIFICATION_TOKEN", "El link de verificación es inválido o ya fue utilizado.");
    }

    private String normalize(String email) {
        return Optional.ofNullable(email).map(String::trim).map(String::toLowerCase).orElse("");
    }
}
