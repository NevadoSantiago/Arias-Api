package com.arias.auth;

import com.arias.users.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * RefreshToken = token persistido en BD para permitir REVOCACIÓN.
 *
 * <p>Diseño de seguridad:
 * <ul>
 *   <li>Nunca guardamos el token en claro — solo su SHA-256 ({@code tokenHash})</li>
 *   <li>Cada refresh emite NUEVO token + invalida el anterior (rotation)</li>
 *   <li>El usuario puede tener múltiples refresh tokens activos (multi-device)</li>
 *   <li>Logout marca {@code revokedAt} → no se puede usar más</li>
 * </ul>
 */
@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 255, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Null = token activo. Si tiene valor, está revocado. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
