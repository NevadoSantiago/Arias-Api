package com.arias.marketing;

import com.arias.email.EmailProperties;
import com.arias.email.OutgoingEmail;
import com.arias.users.Role;
import com.arias.users.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Gmail sends any mail with links or promotional wording to Promotions: this one has neither. */
@SpringBootTest
class ReviewDiscountEmailTest {

    private static final List<String> FORBIDDEN = List.of("descuento", "%", "off", "beneficio", "promo",
        "oferta", "gratis", "precio", "reseña", "google");

    @Autowired private ReviewDiscountEmail email;
    @Autowired private EmailProperties emailProps;

    private User user() {
        return User.builder().id(77L).email("ana@test.arias.com").firstName("Ana")
            .role(Role.EMPLOYEE).active(true).build();
    }

    /** Text a reader sees: markup (and its inline CSS, e.g. width="100%") removed. */
    private static String visibleText(String html) {
        return html.replaceAll("(?s)<[^>]*>", " ");
    }

    @Test
    @DisplayName("subject and branded body thank the customer and mention the dish and the surprise")
    void content() {
        OutgoingEmail mail = email.build(user(), "Milanesa");

        assertThat(mail.to()).isEqualTo("ana@test.arias.com");
        assertThat(mail.subject()).isEqualTo("¿Qué tal estuvo tu Milanesa, Ana?");
        assertThat(mail.html())
            .contains("¡Gracias por elegir Arias, Ana!")
            .contains("Esperamos que hayas disfrutado tu <strong")
            .contains("Milanesa")
            .contains("La próxima vez que entres a la app vas a encontrar una sorpresa.")
            .contains("¡Gracias!<br>Santiago, de Arias");
    }

    @Test
    @DisplayName("uses the branded Arias layout")
    void brandedLayout() {
        assertThat(email.build(user(), "Milanesa").html()).contains("Bodegón · Parrilla");
    }

    @Test
    @DisplayName("without a first name or a dish the wording degrades gracefully")
    void withoutNameOrDish() {
        User u = user();
        u.setFirstName(null);

        OutgoingEmail mail = email.build(u, null);

        assertThat(mail.subject()).isEqualTo("¿Qué tal estuvo tu almuerzo?");
        assertThat(mail.html()).contains("¡Gracias por elegir Arias!").contains("disfrutado tu <strong")
            .contains("almuerzo");
        assertThat(mail.text()).startsWith("¡Gracias por elegir Arias!");
    }

    @Test
    @DisplayName("no links at all: no anchors in the html and no URL in the text")
    void noLinks() {
        OutgoingEmail mail = email.build(user(), "Milanesa");

        assertThat(mail.html()).doesNotContain("<a ").doesNotContain("href");
        assertThat(mail.text()).doesNotContain("http");
    }

    @Test
    @DisplayName("no promotional or review wording in subject, visible html or text")
    void noForbiddenWords() {
        OutgoingEmail mail = email.build(user(), "Milanesa");

        for (String word : FORBIDDEN) {
            assertThat(mail.subject().toLowerCase()).as("subject: " + word).doesNotContain(word);
            assertThat(visibleText(mail.html()).toLowerCase()).as("html: " + word).doesNotContain(word);
            assertThat(mail.text().toLowerCase()).as("text: " + word).doesNotContain(word);
        }
    }

    @Test
    @DisplayName("text alternative mirrors the note")
    void textPart() {
        String text = email.build(user(), "Milanesa").text();

        assertThat(text).startsWith("¡Gracias por elegir Arias, Ana!")
            .contains("tu Milanesa.")
            .contains("La próxima vez que entres a la app vas a encontrar una sorpresa.")
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
