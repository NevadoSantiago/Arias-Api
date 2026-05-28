package com.arias.auth;

import com.arias.common.exception.BusinessException;
import com.arias.email.PasswordResetEmails;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class PasswordResetService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final UserRepository userRepo;
    private final PasswordResetTokenRepository tokenRepo;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetEmails emails;
    private final Clock clock;

    public PasswordResetService(
        UserRepository userRepo,
        PasswordResetTokenRepository tokenRepo,
        JwtService jwtService,
        PasswordEncoder passwordEncoder,
        PasswordResetEmails emails,
        Clock clock
    ) {
        this.userRepo = userRepo;
        this.tokenRepo = tokenRepo;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.emails = emails;
        this.clock = clock;
    }

    @Transactional
    public void requestReset(String email) {
        String normalized = Optional.ofNullable(email).map(String::trim).map(String::toLowerCase).orElse("");

        Optional<User> maybeUser = userRepo.findByEmail(normalized);
        if (maybeUser.isEmpty()) return;

        User user = maybeUser.get();
        if (!Boolean.TRUE.equals(user.getActive()) || user.getPasswordHash() == null) return;

        Instant now = clock.instant();
        tokenRepo.invalidateAllForUser(user.getId(), now);

        String tokenValue = jwtService.generateRefreshTokenValue();
        String tokenHash = jwtService.hashRefreshToken(tokenValue);

        PasswordResetToken entity = PasswordResetToken.builder()
            .user(user)
            .tokenHash(tokenHash)
            .expiresAt(now.plus(TOKEN_TTL))
            .build();

        tokenRepo.save(entity);

        emails.sendResetLink(user.getEmail(), user.getFirstName(), tokenValue);
    }

    @Transactional
    public void resetPassword(String tokenValue, String newPassword) {
        String tokenHash = jwtService.hashRefreshToken(tokenValue);

        PasswordResetToken token = tokenRepo.findByTokenHash(tokenHash)
            .orElseThrow(() -> BusinessException.badRequest("INVALID_RESET_TOKEN", "El link es inválido o ya fue utilizado."));

        Instant now = clock.instant();

        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(now)) {
            throw BusinessException.badRequest("INVALID_RESET_TOKEN", "El link es inválido o ya fue utilizado.");
        }

        token.setUsedAt(now);
        tokenRepo.save(token);

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepo.save(user);
    }
}
