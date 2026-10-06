package com.arias.users;

import com.arias.common.exception.BusinessException;
import com.arias.common.security.JwtUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Tour de primer ingreso del usuario autenticado. */
@RestController
@RequestMapping("/api/v1/me/onboarding-tour")
@RequiredArgsConstructor
public class MeOnboardingTourController {

    private final UserRepository userRepo;

    /** Marca el tour como visto. Idempotente: si ya estaba marcado conserva la primera fecha. */
    @PostMapping("/seen")
    @Transactional
    public ResponseEntity<Void> markSeen(@AuthenticationPrincipal JwtUser principal) {
        User user = userRepo.findById(principal.userId())
            .orElseThrow(() -> BusinessException.notFound("user-not-found", "Usuario no encontrado"));
        if (user.getOnboardingTourSeenAt() == null) {
            user.setOnboardingTourSeenAt(Instant.now());
        }
        return ResponseEntity.noContent().build();
    }
}
