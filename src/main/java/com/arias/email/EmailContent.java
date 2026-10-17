package com.arias.email;

/**
 * Contenido de un mail para {@link EmailLayout#render}.
 *
 * @param title           título (texto plano; el layout lo escapa)
 * @param bodyHtml        bloques de cuerpo ya seguros, armados con los helpers de {@link EmailLayout}
 * @param cta             botón opcional ({@code null} = sin botón)
 * @param footerText      texto del pie ({@code null} = "Familia Mazzariello · Desde 2015")
 * @param belowFooterHtml HTML opcional bajo la tarjeta (ej. link de baja)
 */
public record EmailContent(String title, String bodyHtml, Cta cta, String footerText, String belowFooterHtml) {

    /** Botón de llamada a la acción: etiqueta (texto plano) y URL (el layout escapa ambos). */
    public record Cta(String label, String url) {}

    public static EmailContent of(String title, String bodyHtml) {
        return new EmailContent(title, bodyHtml, null, null, null);
    }

    public EmailContent withCta(String label, String url) {
        return new EmailContent(title, bodyHtml, new Cta(label, url), footerText, belowFooterHtml);
    }

    public EmailContent withFooter(String text) {
        return new EmailContent(title, bodyHtml, cta, text, belowFooterHtml);
    }

    public EmailContent withBelowFooter(String html) {
        return new EmailContent(title, bodyHtml, cta, footerText, html);
    }
}
