package com.arias.auth;

import com.arias.auth.AuthService.AuthResult;
import com.arias.common.exception.InvalidCredentialsException;
import com.arias.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Manejo del ciclo de vida de los refresh tokens.
 *
 * <p>El valor REAL del refresh token nunca se persiste — solo su SHA-256 hash.
 * Eso significa que si la BD se compromete, los refresh tokens existentes
 * no se pueden usar (aunque al atacante le sirvan para identificar usuarios
 * con sesiones activas, info menor).
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository repo;
    private final JwtService jwtService;

    public RefreshTokenService(RefreshTokenRepository repo, JwtService jwtService) {
        this.repo = repo;
        this.jwtService = jwtService;
    }

    /** Crea un nuevo refresh token para el user y lo persiste hasheado. */
    @Transactional
    public String issueFor(User user) {
        String tokenValue = jwtService.generateRefreshTokenValue();
        String tokenHash = jwtService.hashRefreshToken(tokenValue);

        RefreshToken entity = RefreshToken.builder()
            .user(user)
            .tokenHash(tokenHash)
            .expiresAt(jwtService.computeRefreshTokenExpiry())
            .build();

        repo.save(entity);
        return tokenValue;
    }

    /**
     * Rotation: valida el refresh viejo, lo revoca, y emite NUEVO access + refresh.
     * Si el refresh es inválido, expirado o ya estaba revocado → 401.
     */
    @Transactional
    public AuthResult rotate(String oldTokenValue) {
        if (oldTokenValue == null || oldTokenValue.isBlank()) {
            throw new InvalidCredentialsException();
        }

        String oldHash = jwtService.hashRefreshToken(oldTokenValue);
        RefreshToken old = repo.findByTokenHash(oldHash)
            .orElseThrow(InvalidCredentialsException::new);

        Instant now = Instant.now();
        if (old.getRevokedAt() != null || old.getExpiresAt().isBefore(now)) {
            throw new InvalidCredentialsException();
        }

        User user = old.getUser();
        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new InvalidCredentialsException();
        }

        // Revocamos el viejo
        old.setRevokedAt(now);
        repo.save(old);

        // Emitimos par nuevo
        String newAccess = jwtService.issueAccessToken(user);
        String newRefresh = issueFor(user);
        return new AuthResult(newAccess, newRefresh);
    }

    @Transactional
    public void revoke(String tokenValue) {
        String hash = jwtService.hashRefreshToken(tokenValue);
        repo.revokeByTokenHash(hash, Instant.now());
    }
}
