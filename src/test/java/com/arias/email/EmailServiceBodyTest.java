package com.arias.email;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The Resend payload: optional text part and optional sender override. */
class EmailServiceBodyTest {

    private final EmailService service =
        new EmailService(new EmailProperties("key", "no-reply@arias.test", "Arias", "http://localhost"));

    @Test
    void plainEmailKeepsGlobalFromAndHasNoText() {
        Map<String, Object> body = service.emailBody(new OutgoingEmail("a@b.c", "Hola", "<p>x</p>"));

        assertThat(body).containsEntry("from", "Arias <no-reply@arias.test>")
            .containsEntry("html", "<p>x</p>").doesNotContainKey("text");
    }

    @Test
    void textAndFromOverrideAreSentWhenPresent() {
        Map<String, Object> body = service.emailBody(
            new OutgoingEmail("a@b.c", "Hola", "<p>x</p>", "x", "Santiago <s@arias.test>"));

        assertThat(body).containsEntry("from", "Santiago <s@arias.test>").containsEntry("text", "x")
            .containsEntry("to", List.of("a@b.c"));
    }
}
