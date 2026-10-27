package com.arias.marketing;

import com.arias.email.EmailContent;
import com.arias.email.EmailLayout;
import com.arias.email.EmailProperties;
import com.arias.email.OutgoingEmail;
import com.arias.users.User;
import com.arias.users.notifications.NewsUnsubscribeToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Launch email: thanks the customer for trying Arias, asks for an optional Google opinion and
 * announces the account discount. The discount block is deliberately separate from the review
 * ask and never mentions it (Google's review policy: no incentives for reviews).
 */
@Component
@RequiredArgsConstructor
public class ReviewDiscountEmail {

    private static final String SERIF = "'Playfair Display', Georgia, serif";
    private static final String RED = "#B3252B";

    private final EmailProperties props;
    private final MarketingProperties marketing;
    private final NewsUnsubscribeToken tokens;

    public OutgoingEmail build(User user, String dishName, int discountPercent) {
        String subject = "Gracias por estar entre los primeros 100 clientes: tenés " + discountPercent + "% off";
        return new OutgoingEmail(user.getEmail(), subject, buildHtml(user, dishName, discountPercent));
    }

    private String buildHtml(User user, String dishName, int percent) {
        String dish = dishName != null && !dishName.isBlank()
            ? "tu <strong style=\"font-weight:700;\">" + EmailLayout.escape(dishName) + "</strong>"
            : "tu almuerzo";

        String discountBlock = EmailLayout.labelledBox("Beneficio primeros 100 clientes",
            "<div style=\"font-family:" + SERIF + "; font-size:46px; font-weight:900; line-height:1; color:" + RED + ";\">"
                + percent + "% <span style=\"font-size:20px; font-weight:700;\">de descuento</span></div>"
                + "<p style=\"margin:8px 0 0; font-size:13.5px; line-height:1.55;\">"
                + "En tu próximo pack de almuerzos. Ya está aplicado en tu cuenta, no tenés que hacer nada.</p>");

        String body = EmailLayout.paragraph("Esperamos que hayas disfrutado " + dish + ".")
            + EmailLayout.paragraph("¿Qué tal estuvo? Contanos en Google, nos ayuda mucho.")
            + EmailLayout.ctaButton("Dejar mi opinión", marketing.googleReviewUrl())
            + "<div style=\"height:20px; line-height:20px; font-size:0;\">&nbsp;</div>\n"
            + discountBlock;

        String unsubscribeUrl = props.appUrl() + "/unsubscribe-news?t=" + tokens.generate(user.getId());
        String unsubscribe = "<a href=\"" + EmailLayout.escape(unsubscribeUrl)
            + "\" style=\"color:#66524B; text-decoration:underline;\">No quiero recibir más novedades</a>";

        return EmailLayout.render(EmailContent.of("¡Gracias por probar Arias, " + safeName(user.getFirstName()) + "!", body)
            .withBelowFooter(unsubscribe));
    }

    private static String safeName(String name) {
        return (name != null && !name.isBlank()) ? name : "che";
    }
}
