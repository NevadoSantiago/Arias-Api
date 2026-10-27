package com.arias.users;

import com.arias.common.exception.BusinessException;
import com.arias.common.security.JwtUser;
import com.arias.marketing.MarketingProperties;
import com.arias.orders.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * In-app Google review card. {@code GET} says whether to show it (B2C user, still PENDING, review URL
 * configured, at least one delivered order) and with which dish; the {@code POST}s record that the
 * user opened the link or never wants to see it again.
 */
@RestController
@RequestMapping("/api/v1/me/review-prompt")
@RequiredArgsConstructor
public class MeReviewPromptController {

    private static final ReviewPromptDto HIDDEN = new ReviewPromptDto(false, null, null);

    private final UserRepository userRepo;
    private final OrderItemRepository orderItemRepo;
    private final MarketingProperties marketing;

    public record ReviewPromptDto(boolean show, String dishName, String reviewUrl) {}

    @GetMapping
    @Transactional(readOnly = true)
    public ReviewPromptDto get(@AuthenticationPrincipal JwtUser principal) {
        User user = findUser(principal);
        String url = marketing.googleReviewUrl();
        if (user.getCompany() != null
            || user.getReviewPromptStatus() != ReviewPromptStatus.PENDING
            || url == null || url.isBlank()) {
            return HIDDEN;
        }
        List<String> dishes = orderItemRepo.findDishNamesOfLatestDelivered(user.getId());
        return dishes.isEmpty() ? HIDDEN : new ReviewPromptDto(true, dishes.get(0), url);
    }

    /** Idempotent. NEVER wins over OPENED, and OPENED never downgrades NEVER. */
    @PostMapping("/{action}")
    @Transactional
    public ResponseEntity<Void> mark(@AuthenticationPrincipal JwtUser principal, @PathVariable String action) {
        ReviewPromptStatus target = switch (action) {
            case "opened" -> ReviewPromptStatus.OPENED;
            case "never" -> ReviewPromptStatus.NEVER;
            default -> throw BusinessException.badRequest("invalid-review-prompt-action", "Acción inválida");
        };
        User user = findUser(principal);
        if (user.getReviewPromptStatus() != ReviewPromptStatus.NEVER) {
            user.setReviewPromptStatus(target);
        }
        return ResponseEntity.noContent().build();
    }

    private User findUser(JwtUser principal) {
        return userRepo.findById(principal.userId())
            .orElseThrow(() -> BusinessException.notFound("user-not-found", "Usuario no encontrado"));
    }
}
