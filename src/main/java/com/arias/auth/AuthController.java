package com.arias.auth;

import com.arias.auth.AuthService.AuthResult;
import com.arias.auth.dto.*;
import com.arias.common.security.JwtUser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Endpoints de autenticación.
 *
 * <p>Convención de tokens:
 * <ul>
 *   <li><b>Access token</b>: en el body de la response, header Authorization en cada request</li>
 *   <li><b>Refresh token</b>: en cookie httpOnly, scope Path=/api/v1/auth (no se manda a otros paths)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "refresh";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final JwtProperties jwtProperties;
    private final boolean cookieSecure;
    private final String cookieSameSite;

    public AuthController(
        AuthService authService,
        PasswordResetService passwordResetService,
        JwtProperties jwtProperties,
        @Value("${arias.cookie.secure:false}") boolean cookieSecure,
        @Value("${arias.cookie.same-site:Lax}") String cookieSameSite
    ) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.jwtProperties = jwtProperties;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
    }

    @PostMapping("/check-email")
    public CheckEmailResponse checkEmail(@Valid @RequestBody CheckEmailRequest req) {
        return authService.checkEmail(req.email());
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        AuthResult result = authService.login(req.email(), req.password());
        return withRefreshCookie(result);
    }

    @PostMapping("/first-login")
    public ResponseEntity<TokenResponse> firstLogin(@Valid @RequestBody FirstLoginRequest req) {
        AuthResult result = authService.firstLogin(req);
        return withRefreshCookie(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
        @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String refreshToken
    ) {
        AuthResult result = authService.refresh(refreshToken);
        return withRefreshCookie(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String refreshToken
    ) {
        authService.logout(refreshToken);

        // Borra la cookie del browser seteando Max-Age=0
        ResponseCookie clear = baseCookie("", Duration.ZERO).build();

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, clear.toString())
            .build();
    }

    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        passwordResetService.requestReset(req.email());
        return Map.of("message", "Si el email existe, te enviamos instrucciones.");
    }

    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordResetService.resetPassword(req.token(), req.newPassword());
        return Map.of("message", "Contraseña actualizada correctamente.");
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal JwtUser user) {
        return authService.me(user.userId());
    }

    /** Helper: arma la response con access en body + refresh en cookie httpOnly. */
    private ResponseEntity<TokenResponse> withRefreshCookie(AuthResult result) {
        ResponseCookie cookie = baseCookie(result.refreshTokenValue(), jwtProperties.refreshTokenTtl())
            .build();

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(new TokenResponse(result.accessToken()));
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, value)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path(REFRESH_COOKIE_PATH)
            .maxAge(maxAge);
    }
}
