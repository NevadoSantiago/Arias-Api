package com.arias.marketing;

import com.arias.email.EmailLayout;
import com.arias.email.EmailProperties;
import com.arias.email.OutgoingEmail;
import com.arias.users.User;
import com.arias.users.notifications.NewsUnsubscribeToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Personal, almost plain-text note after a customer's lunch: asks for an optional Google opinion
 * and mentions, in a separate paragraph, that their next purchase has a special price. Written
 * and styled like a one-to-one email (no branded layout, no buttons, no promotional words) so it
 * lands in the Primary inbox. The special-price paragraph never mentions the review (Google's
 * review policy: no incentives for reviews).
 */
@Component
@RequiredArgsConstructor
public class ReviewDiscountEmail {

    private static final String FONT = "-apple-system, 'Segoe UI', Helvetica, Arial, sans-serif";
    private static final String SPECIAL_PRICE =
        "Por estar entre nuestros primeros 100 clientes, tu próxima compra tiene un precio especial. "
            + "Lo vas a ver en la app.";

    private final EmailProperties props;
    private final MarketingProperties marketing;
    private final NewsUnsubscribeToken tokens;

    public OutgoingEmail build(User user, String dishName) {
        String name = isBlank(user.getFirstName()) ? null : user.getFirstName();
        String dish = isBlank(dishName) ? "almuerzo" : dishName;
        String subject = "¿Qué tal estuvo tu " + dish + (name != null ? ", " + name : "") + "?";
        String unsubscribeUrl = props.appUrl() + "/unsubscribe-news?t=" + tokens.generate(user.getId());
        return new OutgoingEmail(user.getEmail(), subject, html(name, dish, unsubscribeUrl),
            text(name, dish, unsubscribeUrl), sender());
    }

    private String sender() {
        String address = isBlank(marketing.from()) ? props.from() : marketing.from();
        String fromName = isBlank(marketing.fromName()) ? props.fromName() : marketing.fromName();
        return "%s <%s>".formatted(fromName, address);
    }

    private String html(String name, String dish, String unsubscribeUrl) {
        String reviewUrl = EmailLayout.escape(marketing.googleReviewUrl());
        return "<!DOCTYPE html>\n<html lang=\"es\"><head><meta charset=\"utf-8\"></head>\n"
            + "<body style=\"margin:0; padding:0; background:#ffffff;\">\n"
            + "<div style=\"max-width:560px; margin:0 auto; padding:24px 20px; font-family:" + FONT
            + "; font-size:16px; line-height:1.5; color:#222222;\">\n"
            + p("Hola" + (name != null ? " " + EmailLayout.escape(name) : "") + ",")
            + p("Gracias por elegir Arias. Esperamos que hayas disfrutado tu <strong>"
                + EmailLayout.escape(dish) + "</strong>.")
            + p("Si tenés un minuto, nos ayudaría mucho que nos cuentes qué te pareció en Google:<br>"
                + "<a href=\"" + reviewUrl + "\" style=\"color:#1a0dab; text-decoration:underline;\">"
                + "Dejar mi opinión en Google</a>")
            + p(SPECIAL_PRICE)
            + p("¡Gracias!<br>Santiago, de Arias")
            + "<p style=\"margin:32px 0 0; font-size:12px; line-height:1.4; color:#777777;\">"
            + "<a href=\"" + EmailLayout.escape(unsubscribeUrl) + "\" style=\"color:#777777; text-decoration:underline;\">"
            + "No quiero recibir más novedades</a></p>\n</div>\n</body></html>\n";
    }

    private String text(String name, String dish, String unsubscribeUrl) {
        return "Hola" + (name != null ? " " + name : "") + ",\n\n"
            + "Gracias por elegir Arias. Esperamos que hayas disfrutado tu " + dish + ".\n\n"
            + "Si tenés un minuto, nos ayudaría mucho que nos cuentes qué te pareció en Google:\n"
            + marketing.googleReviewUrl() + "\n\n"
            + SPECIAL_PRICE + "\n\n"
            + "¡Gracias!\nSantiago, de Arias\n\n"
            + "No quiero recibir más novedades: " + unsubscribeUrl + "\n";
    }

    private static String p(String html) {
        return "<p style=\"margin:0 0 16px;\">" + html + "</p>\n";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
