package com.arias.auth;

import com.arias.common.exception.InvalidCredentialsException;
import com.arias.credits.CreditLedgerService;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Optional;

/**
 * Login/alta con Google — spec {@code self-registration}, "Inicio de sesión
 * con Google" (diseño §Decisión 9). El backend NUNCA confía en el email/sub
 * que mande el cliente: {@link #loginWithGoogle} valida el ID token
 * completo (firma, audiencia, emisor, expiración) con {@link
 * GoogleIdTokenVerifier} antes de tocar cualquier dato, y además exige que
 * el propio Google reporte {@code email_verified=true} en el payload.
 *
 * <p>Fusión por email normalizado — misma clave que usa {@link AuthService}
 * (diseño §Decisión 9, tabla de fusión):
 * <ul>
 *   <li>No existe el email → crea {@code EMPLOYEE} con {@code google_sub} y
 *       {@code email_verified_at} seteados.</li>
 *   <li>Existe con {@code google_sub = NULL} → se vincula; la contraseña
 *       existente (si la hay) sigue funcionando.</li>
 *   <li>Existe con OTRO {@code google_sub} → error genérico de credenciales,
 *       igual que login/first-login (no revela de quién es la cuenta).</li>
 * </ul>
 *
 * <p>El almuerzo de bienvenida se otorga SOLO cuando esta llamada es la que
 * deja {@code email_verified_at} seteado por primera vez — nunca en el
 * login de una cuenta ya vinculada o ya verificada por correo antes (spec
 * "Sin doble otorgamiento").
 */
@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final UserRepository userRepo;
    private final AuthService authService;
    private final CreditLedgerService creditLedgerService;
    private final GoogleIdTokenVerifier verifier;

    @Transactional
    public AuthService.AuthResult loginWithGoogle(String idTokenValue) {
        GoogleIdToken.Payload payload = verifyToken(idTokenValue);

        // El backend nunca confía en el email de Google sin que Google mismo
        // reporte email_verified=true en el payload (diseño §Seguridad).
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new InvalidCredentialsException();
        }

        String googleSub = payload.getSubject();
        String email = normalize(payload.getEmail());

        Optional<User> existing = userRepo.findByEmail(email);
        User user;
        boolean firstValidation;

        if (existing.isEmpty()) {
            user = User.builder()
                .email(email)
                .firstName(firstNameOf(payload, email))
                .lastName(lastNameOf(payload))
                .role(Role.EMPLOYEE)
                .active(true)
                .googleSub(googleSub)
                .emailVerifiedAt(Instant.now())
                .build();
            userRepo.save(user);
            firstValidation = true;
        } else {
            user = existing.get();
            if (user.getGoogleSub() == null) {
                firstValidation = user.getEmailVerifiedAt() == null;
                user.setGoogleSub(googleSub);
                if (firstValidation) {
                    user.setEmailVerifiedAt(Instant.now());
                }
                userRepo.save(user);
            } else if (!user.getGoogleSub().equals(googleSub)) {
                // No revela si el email existe ni de quién es — mismo trato
                // genérico que login/first-login.
                throw new InvalidCredentialsException();
            } else {
                firstValidation = false;
            }
        }

        boolean welcomeLunchGranted = firstValidation && creditLedgerService.grantWelcomeLunch(user.getId());

        return authService.issueTokens(user, welcomeLunchGranted);
    }

    private GoogleIdToken.Payload verifyToken(String idTokenValue) {
        try {
            GoogleIdToken idToken = verifier.verify(idTokenValue);
            if (idToken == null) {
                // Firma inválida, audiencia/emisor incorrectos o expirado —
                // el verifier devuelve null en vez de tirar excepción para
                // estos casos.
                throw new InvalidCredentialsException();
            }
            return idToken.getPayload();
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            // Token mal formado o error de verificación — mismo trato
            // genérico que cualquier otra credencial inválida.
            throw new InvalidCredentialsException();
        }
    }

    private static String firstNameOf(GoogleIdToken.Payload payload, String email) {
        Object givenName = payload.get("given_name");
        if (givenName != null && !givenName.toString().isBlank()) {
            return givenName.toString();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static String lastNameOf(GoogleIdToken.Payload payload) {
        Object familyName = payload.get("family_name");
        return familyName != null ? familyName.toString() : null;
    }

    private static String normalize(String email) {
        return Optional.ofNullable(email).map(String::trim).map(String::toLowerCase).orElse("");
    }
}
