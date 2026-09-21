package com.arias.credits;

import com.arias.common.security.JwtUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Consulta de saldo e historial del usuario autenticado — spec {@code
 * credit-ledger}. Requiere autenticación (regla por defecto de {@code
 * SecurityConfig: anyRequest().authenticated()}), sin restricción de rol:
 * cualquier usuario autenticado consulta su PROPIA billetera.
 */
@RestController
@RequestMapping("/api/v1/credits")
@RequiredArgsConstructor
public class CreditController {

    private final CreditLedgerService ledgerService;

    @GetMapping("/wallet")
    public CreditWalletDto getWallet(@AuthenticationPrincipal JwtUser user) {
        return CreditWalletDto.from(ledgerService.getWallet(user.userId()));
    }

    @GetMapping("/movements")
    public List<CreditMovementDto> getMovements(@AuthenticationPrincipal JwtUser user) {
        return ledgerService.getMovements(user.userId()).stream()
            .map(CreditMovementDto::from)
            .toList();
    }
}
