package com.arias.email;

import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.Map;

/**
 * Plantilla única de los mails de Arias: banda de mantel a cuadros, papel claro,
 * botón rojo profundo y esquema claro forzado (los clientes en modo oscuro no
 * invierten la tarjeta). Tablas con estilos inline y {@code bgcolor}: Gmail
 * recorta {@code <style>}, así que no se usa.
 *
 * <p>Las fuentes (Playfair Display / Montserrat) se declaran en la pila con
 * fallback Georgia / sistema; no se cargan webfonts.
 */
public final class EmailLayout {

    public static final String DEFAULT_FOOTER = "Familia Mazzariello · Desde 2015";

    private static final String SANS = "Montserrat, 'Segoe UI', sans-serif";
    private static final String SERIF = "'Playfair Display', Georgia, serif";
    private static final String INK = "#1A110F";
    private static final String MUTED = "#66524B";
    private static final String RULE = "#D8CDB6";
    private static final String RED = "#DC2C2E";

    private EmailLayout() {}

    // ─── Layout ─────────────────────────────────────────────────────────

    public static String render(EmailContent c) {
        String footer = c.footerText() != null ? c.footerText() : DEFAULT_FOOTER;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("""
            <!DOCTYPE html>
            <html lang="es">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="light">
            <meta name="supported-color-schemes" content="light">
            <title>Arias</title>
            </head>
            <body style="margin:0; padding:40px 20px; background:#E8DCC4;" bgcolor="#E8DCC4">
            <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" bgcolor="#E8DCC4"><tr><td align="center">
            <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" style="max-width:480px;">
            <tr><td>
            """);
        sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" bgcolor=\"#FFFCF5\" ")
            .append("style=\"background-color:#FFFCF5; border:1px solid ").append(RULE).append("; border-radius:8px;\">\n");
        // Band: plain #FFFCF5 + red rule is the fallback when gradients are ignored.
        sb.append("<tr><td align=\"center\" bgcolor=\"#FFFCF5\" style=\"background-color:#FFFCF5; ")
            .append("background-image: repeating-linear-gradient(90deg, rgba(220,44,46,0.08) 0 12px, transparent 12px 24px), ")
            .append("repeating-linear-gradient(0deg, rgba(220,44,46,0.08) 0 12px, transparent 12px 24px); ")
            .append("border-bottom:2px solid ").append(RED).append("; padding:28px 32px 22px; text-align:center; border-radius:8px 8px 0 0;\">\n");
        sb.append("<div style=\"font-family:").append(SERIF).append("; font-weight:700; font-size:34px; line-height:1.1; letter-spacing:0.06em; color:")
            .append(RED).append(";\">ARIAS</div>\n");
        sb.append("<div style=\"margin-top:6px; font-family:").append(SANS)
            .append("; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.15em; color:#B3252B;\">Bodegón · Parrilla</div>\n");
        sb.append("</td></tr>\n");
        sb.append("<tr><td style=\"padding:32px 36px 36px; font-family:").append(SANS).append("; color:").append(INK).append(";\">\n");
        sb.append("<h2 style=\"margin:0 0 16px; font-family:").append(SERIF).append("; font-size:26px; font-weight:700; color:")
            .append(INK).append(";\">").append(escape(c.title())).append("</h2>\n");
        sb.append(c.bodyHtml() == null ? "" : c.bodyHtml()).append('\n');
        if (c.cta() != null) {
            sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" align=\"left\" style=\"margin:8px 0 0;\"><tr>")
                .append("<td bgcolor=\"#B3252B\" style=\"background-color:#B3252B; border-radius:6px;\">")
                .append("<a href=\"").append(escape(c.cta().url())).append("\" style=\"display:inline-block; background:#B3252B; color:#ffffff; ")
                .append("font-weight:700; font-size:13px; text-transform:uppercase; letter-spacing:1px; text-decoration:none; ")
                .append("padding:14px 24px; border-radius:6px; line-height:18px; font-family:").append(SANS).append(";\">")
                .append(escape(c.cta().label())).append("</a></td></tr></table>\n");
            sb.append("<div style=\"clear:both; line-height:0; font-size:0;\">&nbsp;</div>\n");
        }
        sb.append("</td></tr>\n");
        sb.append("<tr><td align=\"center\" bgcolor=\"#F8F2E6\" style=\"background-color:#F8F2E6; border-top:1px solid ").append(RULE)
            .append("; padding:18px 32px; text-align:center; font-family:").append(SANS)
            .append("; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.15em; color:").append(MUTED)
            .append("; border-radius:0 0 8px 8px;\">").append(escape(footer)).append("</td></tr>\n");
        sb.append("</table>\n");
        if (c.belowFooterHtml() != null && !c.belowFooterHtml().isBlank()) {
            sb.append("<div style=\"padding:18px 0 0; text-align:center; font-family:").append(SANS)
                .append("; font-size:12px; color:").append(MUTED).append(";\">").append(c.belowFooterHtml()).append("</div>\n");
        }
        sb.append("</td></tr></table>\n</td></tr></table>\n</body>\n</html>\n");
        return sb.toString();
    }

    // ─── Body helpers ───────────────────────────────────────────────────

    /** Escapes text for HTML content and attribute values; accents are kept as-is. */
    public static String escape(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }

    public static String paragraph(String html) {
        return "<p style=\"margin:0 0 16px; font-size:15px; line-height:1.6; color:" + INK + ";\">" + html + "</p>\n";
    }

    public static String mutedParagraph(String html) {
        return "<p style=\"margin:0 0 16px; font-size:15px; line-height:1.6; color:" + MUTED + ";\">" + html + "</p>\n";
    }

    /** Box with an uppercase label; {@code innerHtml} must already be safe. */
    public static String labelledBox(String label, String innerHtml) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin:0 0 16px;\"><tr>"
            + "<td bgcolor=\"#F8F2E6\" style=\"background-color:#F8F2E6; border:1px solid " + RULE + "; border-radius:6px; padding:14px 16px; "
            + "font-size:15px; line-height:1.6; color:" + INK + ";\">"
            + "<div style=\"margin:0 0 6px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.15em; color:" + MUTED + ";\">"
            + escape(label) + "</div>" + innerHtml + "</td></tr></table>\n";
    }

    /** {@code escapedItems} must already be HTML-safe. */
    public static String bulletList(List<String> escapedItems) {
        StringBuilder sb = new StringBuilder("<ul style=\"margin:0; padding:0 0 0 18px; font-size:15px; line-height:1.6; color:" + INK + ";\">");
        for (String item : escapedItems) {
            sb.append("<li style=\"margin:0 0 2px;\">").append(item).append("</li>");
        }
        return sb.append("</ul>").toString();
    }

    /** Label/value rows; values must already be HTML-safe, labels are escaped. */
    public static String labelValueTable(List<Map.Entry<String, String>> rows) {
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" "
            + "style=\"margin:0 0 16px; font-size:14px; line-height:1.6;\">");
        for (Map.Entry<String, String> r : rows) {
            sb.append("<tr><td style=\"padding:8px 0; width:110px; border-bottom:1px solid ").append(RULE)
                .append("; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.15em; color:").append(MUTED).append(";\">")
                .append(escape(r.getKey())).append("</td><td style=\"padding:8px 0; border-bottom:1px solid ").append(RULE)
                .append("; color:").append(INK).append(";\">").append(r.getValue()).append("</td></tr>");
        }
        return sb.append("</table>\n").toString();
    }

    /** Table with a 2px red header rule and 1px rules between rows; cells must already be HTML-safe. */
    public static String summaryTable(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" "
            + "style=\"margin:0 0 24px; font-size:14px; line-height:1.6;\"><tr>");
        for (String h : headers) {
            sb.append("<th align=\"left\" style=\"text-align:left; padding:8px 0; border-bottom:2px solid ").append(RED)
                .append("; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.15em; color:").append(MUTED).append(";\">")
                .append(escape(h)).append("</th>");
        }
        sb.append("</tr>");
        for (List<String> row : rows) {
            sb.append("<tr>");
            for (String cell : row) {
                sb.append("<td style=\"padding:8px 0; border-bottom:1px solid ").append(RULE).append("; color:").append(INK).append(";\">")
                    .append(cell).append("</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</table>\n").toString();
    }
}
