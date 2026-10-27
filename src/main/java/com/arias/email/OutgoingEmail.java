package com.arias.email;

/**
 * Un mail listo para salir — unidad del envío batch ({@code POST /emails/batch}).
 *
 * @param text optional plain-text alternative ({@code null} = html only)
 * @param from optional sender header overriding the global one ({@code null} = global)
 */
public record OutgoingEmail(String to, String subject, String html, String text, String from) {

    public OutgoingEmail(String to, String subject, String html) {
        this(to, subject, html, null, null);
    }
}
