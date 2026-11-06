package com.arias.loyalty;

import com.arias.common.security.JwtUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The progress of the caller. B2B users and a disabled feature get {@code {enabled:false}}. */
@RestController
@RequestMapping("/api/v1/loyalty")
@RequiredArgsConstructor
public class LoyaltyController {

    private final LoyaltyService loyaltyService;

    @GetMapping
    public Object get(@AuthenticationPrincipal JwtUser user) {
        if (!loyaltyService.isEnabled() || user.companyId() != null) {
            return LoyaltyDisabledDto.instance();
        }
        return loyaltyService.view(user.userId());
    }
}
