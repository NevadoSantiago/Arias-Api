package com.arias.users.notifications;

import com.arias.common.exception.BusinessException;
import com.arias.common.security.JwtUser;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Preferencias de notificación del usuario autenticado.
 * El unsubscribe vía link del mail (público, sin auth) vive en
 * {@link UnsubscribeReminderController}.
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
@RequiredArgsConstructor
public class MeNotificationsController {

    private final UserRepository userRepo;

    @GetMapping
    @Transactional(readOnly = true)
    public NotificationPreferencesDto get(@AuthenticationPrincipal JwtUser principal) {
        User user = findOrThrow(principal.userId());
        return toDto(user);
    }

    @PutMapping
    @Transactional
    public NotificationPreferencesDto update(
        @AuthenticationPrincipal JwtUser principal,
        @Valid @RequestBody UpdateNotificationPreferencesRequest req
    ) {
        if (req.recibeRecordatorioPedido() == null && req.recibeAvisoRetiro() == null) {
            throw BusinessException.badRequest("no-preferences", "Indicá al menos una preferencia");
        }
        User user = findOrThrow(principal.userId());
        if (req.recibeRecordatorioPedido() != null) user.setRecibeRecordatorioPedido(req.recibeRecordatorioPedido());
        if (req.recibeAvisoRetiro() != null) user.setRecibeAvisoRetiro(req.recibeAvisoRetiro());
        return toDto(user);
    }

    private static NotificationPreferencesDto toDto(User user) {
        return new NotificationPreferencesDto(
            Boolean.TRUE.equals(user.getRecibeRecordatorioPedido()),
            Boolean.TRUE.equals(user.getRecibeAvisoRetiro()));
    }

    private User findOrThrow(Long id) {
        return userRepo.findById(id)
            .orElseThrow(() -> BusinessException.notFound("user-not-found", "Usuario no encontrado"));
    }
}
