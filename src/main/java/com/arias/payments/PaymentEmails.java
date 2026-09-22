package com.arias.payments;

import com.arias.email.EmailService;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Mails del ciclo de compra de créditos (unidad 11) — mismo patrón visual
 * que {@code OrderNotificationEmails} (unidad 12) y {@code WelcomeEmails}.
 * Escucha eventos vía {@code @TransactionalEventListener(AFTER_COMMIT)}: si
 * la transacción del webhook termina en rollback, ningún mail sale.
 */
@Component
@RequiredArgsConstructor
public class PaymentEmails {

    private final EmailService emailService;
    private final UserRepository userRepo;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCredited(CreditPurchaseCreditedEvent event) {
        if (event.userEmail() == null || event.userEmail().isBlank()) return;

        String intro = """
            <p style="line-height: 1.6; margin: 0 0 16px;">Te acreditamos <strong>%d almuerzo(s)</strong> en tu saldo — ¡ya podés pedir!</p>
            """.formatted(event.creditAmount());

        emailService.send(event.userEmail(), "Acreditamos tu compra — Arias", buildHtml("¡Listo!", intro));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReversed(CreditPurchaseReversedEvent event) {
        List<User> admins = userRepo.findActiveSuperAdmins();
        String estadoTexto = event.fullyReversed() ? "revertida por completo" : "revertida parcialmente";

        String introAdmin = """
            <p style="line-height: 1.6; margin: 0 0 16px;">La compra <strong>%s</strong> fue %s por Mercado Pago (reembolso o contracargo): se revirtieron %d almuerzo(s) de %d, acumulando %d ya revertidos en total.</p>
            """.formatted(event.purchaseId(), estadoTexto, event.creditsReversedNow(),
                event.creditAmount(), event.creditsReversedTotal());
        String htmlAdmin = buildHtml("Reembolso de Mercado Pago", introAdmin);
        for (User admin : admins) {
            if (admin.getEmail() == null || admin.getEmail().isBlank()) continue;
            emailService.send(admin.getEmail(), "Reembolso detectado — Arias", htmlAdmin);
        }
    }

    private static String buildHtml(String heading, String introHtml) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f7f1ed; padding: 40px 20px; color: #2a1a14;">
              <table cellpadding="0" cellspacing="0" border="0" align="center" style="max-width: 480px; background: #fffdfb; border-radius: 8px; padding: 32px;">
                <tr><td>
                  <h1 style="font-family: Georgia, serif; color: #c5191d; font-size: 28px; margin: 0 0 8px;">ARIAS</h1>
                  <p style="color: #c5191d; text-transform: uppercase; letter-spacing: 2px; font-size: 11px; margin: 0 0 32px;">Bodegón · Parrilla</p>

                  <h2 style="font-family: Georgia, serif; font-size: 22px; margin: 0 0 16px;">%s</h2>
                  %s

                  <p style="color: #6b5b52; font-size: 11px; margin: 32px 0 0; text-transform: uppercase; letter-spacing: 1px;">Familia Mazzariello · Desde 2015</p>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(heading, introHtml);
    }
}
