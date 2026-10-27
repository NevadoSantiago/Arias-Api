package com.arias.marketing;

import com.arias.email.EmailProperties;
import com.arias.email.OutgoingEmail;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.notifications.NewsUnsubscribeToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "arias.marketing.google-review-url=https://g.page/r/test-review")
class ReviewDiscountEmailTest {

    @Autowired private ReviewDiscountEmail email;
    @Autowired private EmailProperties emailProps;
    @Autowired private NewsUnsubscribeToken tokens;

    private User user() {
        return User.builder().id(77L).email("ana@test.arias.com").firstName("Ana")
            .role(Role.EMPLOYEE).active(true).build();
    }

    @Test
    @DisplayName("subject, greeting, dish, review button and the 10% block")
    void content() {
        OutgoingEmail mail = email.build(user(), "Milanesa", 10);

        assertThat(mail.to()).isEqualTo("ana@test.arias.com");
        assertThat(mail.subject()).isEqualTo("Gracias por estar entre los primeros 100 clientes: tenés 10% off");
        assertThat(mail.html())
            .contains("¡Gracias por probar Arias, Ana!")
            .contains("Esperamos que hayas disfrutado tu <strong")
            .contains("Milanesa")
            .contains("Dejar mi opinión")
            .contains("href=\"https://g.page/r/test-review\"")
            .contains("Beneficio primeros 100 clientes")
            .contains("10%")
            .contains("de descuento")
            .contains("En tu próxima compra.")
            .doesNotContain("próximo pack");
    }

    @Test
    @DisplayName("no expiry date and no packs button")
    void noExpiryNoPacksButton() {
        String html = email.build(user(), "Milanesa", 10).html();

        assertThat(html).doesNotContain("Ver packs").doesNotContain("vence").doesNotContain("Válido")
            .doesNotContain("válido hasta");
        assertThat(Pattern.compile("<a ").matcher(html).results().count())
            .as("review button + unsubscribe link only").isEqualTo(2);
    }

    @Test
    @DisplayName("the discount block never ties the benefit to leaving a review")
    void discountNotTiedToReview() {
        String html = email.build(user(), "Milanesa", 10).html();
        String discountBlock = html.substring(html.indexOf("Beneficio primeros 100 clientes"));

        assertThat(discountBlock.toLowerCase())
            .doesNotContain("opini").doesNotContain("reseña").doesNotContain("google")
            .doesNotContain("a cambio").doesNotContain("por dejar");
    }

    @Test
    @DisplayName("footer carries a news unsubscribe link with a valid token for the user")
    void unsubscribeLink() {
        String html = email.build(user(), "Milanesa", 10).html();

        Matcher m = Pattern.compile("href=\"" + Pattern.quote(emailProps.appUrl()) + "/unsubscribe-news\\?t=([^\"]+)\"")
            .matcher(html);
        assertThat(m.find()).isTrue();
        assertThat(tokens.verify(m.group(1))).contains(77L);
        assertThat(html).contains("No quiero recibir más novedades");
    }

    @Test
    @DisplayName("without a dish name it still reads well")
    void withoutDish() {
        assertThat(email.build(user(), null, 10).html()).contains("Esperamos que hayas disfrutado tu almuerzo.");
    }

    @Test
    @DisplayName("without a first name the title has no name and no filler")
    void withoutFirstName() {
        User u = user();
        u.setFirstName(null);

        String html = email.build(u, "Milanesa", 10).html();

        assertThat(html).contains("¡Gracias por probar Arias!").doesNotContain("Arias, che");
    }

    @Test
    @DisplayName("user data is escaped")
    void escapes() {
        User u = user();
        u.setFirstName("<b>Ana</b>");

        String html = email.build(u, "\"><img src=x>", 10).html();

        assertThat(html).doesNotContain("<b>Ana</b>").doesNotContain("<img src=x>");
    }
}
