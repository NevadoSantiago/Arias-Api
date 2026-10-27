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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "arias.marketing.google-review-url=https://g.page/r/test-review")
class ReviewDiscountEmailTest {

    private static final List<String> PROMO_WORDS =
        List.of("descuento", "%", "off", "beneficio", "promo", "oferta", "gratis");

    @Autowired private ReviewDiscountEmail email;
    @Autowired private EmailProperties emailProps;
    @Autowired private NewsUnsubscribeToken tokens;

    private User user() {
        return User.builder().id(77L).email("ana@test.arias.com").firstName("Ana")
            .role(Role.EMPLOYEE).active(true).build();
    }

    @Test
    @DisplayName("subject and body are a personal note naming the dish and the customer")
    void content() {
        OutgoingEmail mail = email.build(user(), "Milanesa");

        assertThat(mail.to()).isEqualTo("ana@test.arias.com");
        assertThat(mail.subject()).isEqualTo("¿Qué tal estuvo tu Milanesa, Ana?");
        assertThat(mail.html())
            .contains("Hola Ana,")
            .contains("Gracias por elegir Arias. Esperamos que hayas disfrutado tu <strong")
            .contains("Milanesa")
            .contains("Si tenés un minuto, nos ayudaría mucho que nos cuentes qué te pareció en Google:")
            .contains("href=\"https://g.page/r/test-review\"")
            .contains("Dejar mi opinión en Google")
            .contains("Por estar entre nuestros primeros 100 clientes, tu próxima compra tiene un precio especial. Lo vas a ver en la app.")
            .contains("¡Gracias!")
            .contains("Santiago, de Arias");
    }

    @Test
    @DisplayName("without a first name or a dish the wording degrades gracefully")
    void withoutNameOrDish() {
        User u = user();
        u.setFirstName(null);

        OutgoingEmail mail = email.build(u, null);

        assertThat(mail.subject()).isEqualTo("¿Qué tal estuvo tu almuerzo?");
        assertThat(mail.html()).contains("Hola,").doesNotContain("Hola Ana").contains("disfrutado tu <strong")
            .contains("almuerzo");
        assertThat(mail.text()).startsWith("Hola,");
    }

    @Test
    @DisplayName("no promotional words in subject, html or text (keeps the mail out of Promotions)")
    void noPromoWords() {
        OutgoingEmail mail = email.build(user(), "Milanesa");

        for (String word : PROMO_WORDS) {
            assertThat(mail.subject().toLowerCase()).as("subject: " + word).doesNotContain(word);
            assertThat(mail.html().toLowerCase()).as("html: " + word).doesNotContain(word);
            assertThat(mail.text().toLowerCase()).as("text: " + word).doesNotContain(word);
        }
    }

    @Test
    @DisplayName("plain, branded-free html: no images, no layout header, only the Google and unsubscribe links")
    void minimalHtml() {
        String html = email.build(user(), "Milanesa").html();

        assertThat(html).doesNotContain("<img").doesNotContain("ARIAS</div>").doesNotContain("Bodegón");
        assertThat(Pattern.compile("<a ").matcher(html).results().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the special-price paragraph never mentions opinión, reseña or Google")
    void specialPriceNotTiedToReview() {
        String html = email.build(user(), "Milanesa").html();
        String after = html.substring(html.indexOf("Por estar entre nuestros primeros 100 clientes"));
        String paragraph = after.substring(0, after.indexOf("</p>")).toLowerCase();

        assertThat(paragraph).doesNotContain("opini").doesNotContain("reseña").doesNotContain("google")
            .doesNotContain("a cambio").doesNotContain("por dejar");
    }

    @Test
    @DisplayName("footer carries a news unsubscribe link with a valid token for the user")
    void unsubscribeLink() {
        OutgoingEmail mail = email.build(user(), "Milanesa");

        Matcher m = Pattern.compile("href=\"" + Pattern.quote(emailProps.appUrl()) + "/unsubscribe-news\\?t=([^\"]+)\"")
            .matcher(mail.html());
        assertThat(m.find()).isTrue();
        assertThat(tokens.verify(m.group(1))).contains(77L);
        assertThat(mail.html()).contains("No quiero recibir más novedades");
        assertThat(mail.text()).contains("No quiero recibir más novedades: " + emailProps.appUrl() + "/unsubscribe-news?t=");
    }

    @Test
    @DisplayName("text alternative mirrors the note and carries the review link")
    void textPart() {
        String text = email.build(user(), "Milanesa").text();

        assertThat(text).startsWith("Hola Ana,")
            .contains("tu Milanesa.")
            .contains("https://g.page/r/test-review")
            .contains("tu próxima compra tiene un precio especial")
            .contains("Santiago, de Arias");
    }

    @Test
    @DisplayName("sender is the marketing from-name with the global address unless a marketing address is set")
    void sender() {
        assertThat(email.build(user(), "Milanesa").from())
            .isEqualTo("Santiago de Arias <" + emailProps.from() + ">");
    }

    @Test
    @DisplayName("user data is escaped")
    void escapes() {
        User u = user();
        u.setFirstName("<b>Ana</b>");

        String html = email.build(u, "\"><img src=x>").html();

        assertThat(html).doesNotContain("<b>Ana</b>").doesNotContain("<img src=x>");
    }
}
