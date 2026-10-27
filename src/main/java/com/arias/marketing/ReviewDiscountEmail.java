package com.arias.marketing;

import com.arias.credits.discount.AccountDiscountService;
import com.arias.email.EmailContent;
import com.arias.email.EmailLayout;
import com.arias.email.EmailProperties;
import com.arias.email.OutgoingEmail;
import com.arias.users.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Thank-you note after a customer's lunch, in the branded Arias layout. It has NO links and no
 * promotional wording on purpose: in Gmail placement tests any link or "special price" wording
 * sent it to Promotions. It only hints that a surprise waits in the app.
 */
@Component
@RequiredArgsConstructor
public class ReviewDiscountEmail {

    private static final String CTA_LABEL = "Entrar a la app";
    private static final long DAYS =AccountDiscountService.VALIDITY.toDays();
    private static final String SURPRISE = "La próxima vez que entres a la app vas a encontrar una sorpresa. "
        + "Tenés " + DAYS + " días antes de que venza.";

    private final EmailProperties props;
    private final MarketingProperties marketing;

    public OutgoingEmail build(User user, String dishName) {
        String name = isBlank(user.getFirstName()) ? null : user.getFirstName();
        String dish = isBlank(dishName) ? "almuerzo" : dishName;
        String subject = (name != null ? name + ", tu" : "Tu") + " almuerzo vino con sorpresa";
        String title = "¡Gracias por elegir Arias" + (name != null ? ", " + name : "") + "!";
        return new OutgoingEmail(user.getEmail(), subject, html(title, dish), text(title, dish), sender());
    }

    private String sender() {
        String address = isBlank(marketing.from()) ? props.from() : marketing.from();
        String fromName = isBlank(marketing.fromName()) ? props.fromName() : marketing.fromName();
        return "%s <%s>".formatted(fromName, address);
    }

    private String html(String title, String dish) {
        String body = EmailLayout.paragraph("Esperamos que hayas disfrutado tu <strong>"
                + EmailLayout.escape(dish) + "</strong>.")
            + EmailLayout.paragraph(SURPRISE)
            + EmailLayout.paragraph("¡Gracias!<br>Equipo de Arias");
        return EmailLayout.render(EmailContent.of(title, body).withCta(CTA_LABEL, props.appUrl()));
    }

    private String text(String title, String dish) {
        return title + "\n\n"
            + "Esperamos que hayas disfrutado tu " + dish + ".\n\n"
            + SURPRISE + "\n\n"
            + "¡Gracias!\nEquipo de Arias\n\n"
            + CTA_LABEL + ": " + props.appUrl() + "\n";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
