package com.arias.users.notifications;

import com.arias.common.exception.BusinessException;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint PÚBLICO para el link "no quiero más recordatorios" del mail.
 * Valida el HMAC del token, desactiva el flag del usuario, idempotente.
 */
@RestController
@RequestMapping("/api/v1/me/unsubscribe-reminder")
@RequiredArgsConstructor
public class UnsubscribeReminderController {

    private final ReminderUnsubscribeToken tokens;
    private final UserRepository userRepo;

    @PostMapping
    @Transactional
    public UnsubscribeResultDto unsubscribe(@RequestParam("t") String token) {
        Long userId = tokens.verify(token)
            .orElseThrow(() -> BusinessException.badRequest("invalid-token",
                "Link inválido o vencido"));

        User user = userRepo.findById(userId)
            .orElseThrow(() -> BusinessException.notFound("user-not-found",
                "Usuario no encontrado"));

        user.setRecibeRecordatorioPedido(false);
        return new UnsubscribeResultDto(user.getEmail());
    }

    public record UnsubscribeResultDto(String email) {}
}
