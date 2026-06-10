package com.arias.email;

/** Un mail listo para salir — unidad del envío batch ({@code POST /emails/batch}). */
public record OutgoingEmail(String to, String subject, String html) {
}
