package com.arias.auth;

import com.arias.auth.dto.*;
import com.arias.common.exception.BusinessException;
import com.arias.common.exception.InvalidCredentialsException;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Lógica de autenticación.
 *
 * <p>Decisiones de seguridad clave:
 * <ul>
 *   <li><b>check-email</b>: solo revela "primer-login pendiente" — no diferencia entre
 *       email registrado vs inexistente. Mitiga enumeración de cuentas.</li>
 *   <li><b>login</b> / <b>first-login</b>: errores genéricos
 *       ("Email o contraseña incorrectos") independientemente del problema real.</li>
 *   <li><b>Refresh tokens</b>: rotation en cada uso (revoca el viejo, emite uno nuevo).
 *       Si un atacante usa un refresh viejo después del refresh real, queda bloqueado.</li>
 * </ul>
 */
@Service
public class AuthService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
        UserRepository userRepo,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        RefreshTokenService refreshTokenService
    ) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    /** Solo retorna si el email requiere first-login. No revela existencia. */
    public CheckEmailResponse checkEmail(String email) {
        boolean requiresFirstLogin = userRepo.isFirstLoginPending(normalize(email));
        return new CheckEmailResponse(requiresFirstLogin);
    }

    /**
     * Login normal: email + password.
     * Emite access (JWT) + refresh token (opaco, persistido como hash).
     */
    @Transactional
    public AuthResult login(String email, String password) {
        User user = userRepo.findByEmail(normalize(email))
            .orElseThrow(InvalidCredentialsException::new);

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new InvalidCredentialsException();
        }
        // Si el usuario pertenece a una empresa desactivada, no puede operar.
        // Devolvemos el mismo error genérico para no leakear info.
        if (user.getCompany() != null && !Boolean.TRUE.equals(user.getCompany().getEnabled())) {
            throw new InvalidCredentialsException();
        }
        if (user.getPasswordHash() == null) {
            // Existe pero nunca seteó password — tiene que ir por first-login
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        user.setLastLoginAt(Instant.now());
        userRepo.save(user);

        return issueTokens(user);
    }

    /**
     * First login: empleado nuevo setea nombre + password.
     * Solo válido si el usuario existe en la whitelist Y aún no tiene password.
     */
    @Transactional
    public AuthResult firstLogin(FirstLoginRequest req) {
        User user = userRepo.findByEmail(normalize(req.email()))
            .orElseThrow(InvalidCredentialsException::new);

        if (!Boolean.TRUE.equals(user.getActive()) || user.getPasswordHash() != null) {
            // Ya tiene password o está deshabilitado → genérico para no leakear
            throw new InvalidCredentialsException();
        }
        // Bloqueo si la empresa del user está desactivada
        if (user.getCompany() != null && !Boolean.TRUE.equals(user.getCompany().getEnabled())) {
            throw new InvalidCredentialsException();
        }

        Instant now = Instant.now();
        user.setFirstName(req.firstName());
        user.setLastName(req.lastName());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setFirstLoginAt(now);
        user.setLastLoginAt(now);
        userRepo.save(user);

        return issueTokens(user);
    }

    /** Refresh: revoca el token viejo y emite uno nuevo (rotation). */
    @Transactional
    public AuthResult refresh(String refreshTokenValue) {
        return refreshTokenService.rotate(refreshTokenValue);
    }

    /** Logout: revoca el refresh token. El access (JWT) sigue válido hasta exp. */
    @Transactional
    public void logout(String refreshTokenValue) {
        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            refreshTokenService.revoke(refreshTokenValue);
        }
    }

    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        User user = userRepo.findById(userId)
            .orElseThrow(InvalidCredentialsException::new);

        return toMeResponse(user);
    }

    /**
     * Completa teléfono y apodo — lo que Google no provee (diseño §Decisión
     * 9, unidad 5). Requiere sesión ya válida (el caller es un endpoint
     * autenticado); no reemite tokens, solo actualiza el perfil.
     */
    @Transactional
    public MeResponse completeProfile(Long userId, CompleteProfileRequest req) {
        User user = userRepo.findById(userId)
            .orElseThrow(InvalidCredentialsException::new);

        String phone = PhoneNumbers.normalizeE164(req.phone())
            .orElseThrow(() -> BusinessException.badRequest(
                "INVALID_PHONE", "El teléfono no tiene un formato válido"));

        if (!phone.equals(user.getPhone()) && userRepo.existsActivePhone(phone)) {
            throw BusinessException.conflict(
                "PHONE_ALREADY_REGISTERED", "Ese teléfono ya está asociado a una cuenta");
        }

        user.setPhone(phone);
        user.setNickname(req.nickname());
        userRepo.save(user);

        return toMeResponse(user);
    }

    private MeResponse toMeResponse(User user) {
        return new MeResponse(
            user.getId(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.getRole(),
            user.getCompany() != null ? user.getCompany().getId() : null,
            user.getCompany() != null ? user.getCompany().getNombre() : null,
            user.getCategory() != null ? user.getCategory().getId() : null,
            user.getEmailVerifiedAt() != null,
            user.isProfileComplete()
        );
    }

    /** Emite tokens sin otorgamiento de almuerzo de bienvenida (login/first-login normales). */
    AuthResult issueTokens(User user) {
        return issueTokens(user, false);
    }

    /**
     * Package-private: {@link RegistrationService#verifyEmail} y {@link
     * GoogleAuthService#loginWithGoogle} la reutilizan para el auto-login,
     * reportando en el mismo response si ESTA llamada otorgó el almuerzo de
     * bienvenida (spec {@code self-registration}, "Otorgamiento único") —
     * ver {@code CreditLedgerService#grantWelcomeLunch}.
     */
    AuthResult issueTokens(User user, boolean welcomeLunchGranted) {
        String accessToken = jwtService.issueAccessToken(user);
        String refreshTokenValue = refreshTokenService.issueFor(user);
        return new AuthResult(accessToken, refreshTokenValue, welcomeLunchGranted);
    }

    private String normalize(String email) {
        return Optional.ofNullable(email).map(String::trim).map(String::toLowerCase).orElse("");
    }

    /**
     * Resultado interno: contiene el access token (para el body) y el refresh
     * token CRUDO (para meter en la cookie httpOnly). El controller los separa.
     *
     * <p>{@code welcomeLunchGranted} es {@code true} únicamente cuando ESTA
     * llamada disparó el movimiento {@code WELCOME_GRANT} — nunca en un
     * login posterior de la misma cuenta (spec "Sin doble otorgamiento").
     */
    public record AuthResult(String accessToken, String refreshTokenValue, boolean welcomeLunchGranted) {}
}
