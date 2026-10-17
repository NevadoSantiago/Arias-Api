package com.arias.payments;

import com.arias.email.EmailContent;
import com.arias.email.EmailLayout;
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

        String intro = EmailLayout.paragraph("Te acreditamos <strong>%d almuerzo(s)</strong> en tu saldo — ¡ya podés pedir!"
            .formatted(event.creditAmount()));

        emailService.send(event.userEmail(), "Acreditamos tu compra — Arias", buildHtml("¡Listo!", intro));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReversed(CreditPurchaseReversedEvent event) {
        List<User> admins = userRepo.findActiveSuperAdmins();
        String estadoTexto = event.fullyReversed() ? "revertida por completo" : "revertida parcialmente";

        String introAdmin = EmailLayout.paragraph(
            "La compra <strong>%s</strong> fue %s por Mercado Pago (reembolso o contracargo): se revirtieron %d almuerzo(s) de %d, acumulando %d ya revertidos en total."
                .formatted(event.purchaseId(), estadoTexto, event.creditsReversedNow(),
                    event.creditAmount(), event.creditsReversedTotal()));
        String htmlAdmin = buildHtml("Reembolso de Mercado Pago", introAdmin);
        for (User admin : admins) {
            if (admin.getEmail() == null || admin.getEmail().isBlank()) continue;
            emailService.send(admin.getEmail(), "Reembolso detectado — Arias", htmlAdmin);
        }
    }

    private static String buildHtml(String heading, String introHtml) {
        return EmailLayout.render(EmailContent.of(heading, introHtml));
    }
}
