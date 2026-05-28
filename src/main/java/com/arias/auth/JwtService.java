package com.arias.auth;

import com.arias.users.Role;
import com.arias.users.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/**
 * Generación y validación de tokens.
 *
 * <p>Access token = JWT (HS256), contiene claims del usuario.
 * <br>Refresh token = string aleatorio opaco (no JWT), 256 bits de entropía.
 *
 * <p>El refresh se guarda en la BD como SHA-256 hash del valor real,
 * para que ni siquiera un dump de la DB exponga refresh tokens válidos.
 */
@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey signingKey;
    private final SecureRandom random = new SecureRandom();

    public JwtService(JwtProperties props) {
        this.props = props;
        // jjwt requiere min 256 bits para HS256 (32 chars ASCII)
        byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                "arias.jwt.secret debe tener al menos 32 caracteres (256 bits). Configurar JWT_SECRET env var.");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Emite un access token JWT firmado para el usuario.
     * Claims: sub=userId, email, role, companyId (opt), categoryId (opt).
     */
    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
            .subject(String.valueOf(user.getId()))
            .claim("email", user.getEmail())
            .claim("role", user.getRole().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(props.accessTokenTtl())))
            .signWith(signingKey);

        if (user.getCompany() != null) {
            builder.claim("companyId", user.getCompany().getId());
        }
        // categoryId: prioriza la del user. Si es null (típico CompanyAdmin),
        // cae a la categoriaDefault de la empresa — así el admin puede operar
        // como empleado y ve el mismo menú que sus empleados por default.
        Long categoryId = null;
        if (user.getCategory() != null) {
            categoryId = user.getCategory().getId();
        } else if (user.getCompany() != null && user.getCompany().getCategoriaDefault() != null) {
            categoryId = user.getCompany().getCategoriaDefault().getId();
        }
        if (categoryId != null) {
            builder.claim("categoryId", categoryId);
        }

        return builder.compact();
    }

    /**
     * Valida la firma + expiración del JWT y devuelve los claims.
     * Lanza JwtException si es inválido (caller debe responder 401).
     */
    public Claims parseAccessToken(String token) throws JwtException {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /**
     * Genera un refresh token opaco: 32 bytes random codificados en base64-url.
     * El valor crudo se devuelve al cliente UNA SOLA VEZ.
     * En la BD persistimos solo el SHA-256 del valor.
     */
    public String generateRefreshTokenValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hash SHA-256 del refresh token en formato hex (64 chars). */
    public String hashRefreshToken(String tokenValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(tokenValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible — JVM rota", e);
        }
    }

    public Instant computeRefreshTokenExpiry() {
        return Instant.now().plus(props.refreshTokenTtl());
    }

    /** Extrae rol del JWT (para el JwtAuthenticationFilter). */
    public Role extractRole(Claims claims) {
        return Role.valueOf(claims.get("role", String.class));
    }
}
