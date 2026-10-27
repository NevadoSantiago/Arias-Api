package com.arias.users.notifications;

import com.arias.common.exception.BusinessException;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint PÚBLICO para el link "No quiero recibir más novedades" de los mails de
 * marketing. Valida el HMAC del token, apaga {@code recibeNovedades}, idempotente.
 */
@RestController
@RequestMapping("/api/v1/me/unsubscribe-news")
@RequiredArgsConstructor
public class UnsubscribeNewsController {

    private final NewsUnsubscribeToken tokens;
    private final UserRepository userRepo;

    @PostMapping
    @Transactional
    public UnsubscribeReminderController.UnsubscribeResultDto unsubscribe(@RequestParam("t") String token) {
        Long userId = tokens.verify(token)
            .orElseThrow(() -> BusinessException.badRequest("invalid-token",
                "Link inválido o vencido"));

        User user = userRepo.findById(userId)
            .orElseThrow(() -> BusinessException.notFound("user-not-found",
                "Usuario no encontrado"));

        user.setRecibeNovedades(false);
        return new UnsubscribeReminderController.UnsubscribeResultDto(user.getEmail());
    }
}
