package com.arias.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailLayoutTest {

    private static String base() {
        return EmailLayout.render(EmailContent.of("Hola", EmailLayout.paragraph("Cuerpo")));
    }

    @Test
    @DisplayName("render(): documento con metas de esquema claro forzado")
    void forcesLightScheme() {
        String html = base();
        assertThat(html)
            .contains("<!DOCTYPE html>")
            .contains("<html lang=\"es\">")
            .contains("<meta charset=\"utf-8\">")
            .contains("<meta name=\"color-scheme\" content=\"light\">")
            .contains("<meta name=\"supported-color-schemes\" content=\"light\">")
            .contains("<body style=\"margin:0; padding:40px 20px; background:#E8DCC4;\" bgcolor=\"#E8DCC4\">");
    }

    @Test
    @DisplayName("render(): tarjeta de 480px, banda con gradiente, fallback liso y wordmark")
    void cardAndBand() {
        String html = base();
        assertThat(html)
            .contains("max-width:480px")
            .contains("bgcolor=\"#FFFCF5\"")
            .contains("border:1px solid #D8CDB6")
            .contains("border-radius:8px")
            .contains("background-color:#FFFCF5; background-image: repeating-linear-gradient(90deg, rgba(220,44,46,0.08) 0 12px, transparent 12px 24px), repeating-linear-gradient(0deg, rgba(220,44,46,0.08) 0 12px, transparent 12px 24px); border-bottom:2px solid #DC2C2E;")
            .contains("padding:28px 32px 22px")
            .contains("ARIAS")
            .contains("font-size:34px")
            .contains("Bodegón · Parrilla")
            .contains("letter-spacing:0.15em");
    }

    @Test
    @DisplayName("render(): título h2, cuerpo y celda de contenido")
    void titleAndBody() {
        String html = base();
        assertThat(html)
            .contains("padding:32px 36px 36px")
            .contains("<h2 style=\"margin:0 0 16px; font-family:'Playfair Display', Georgia, serif; font-size:26px; font-weight:700; color:#1A110F;\">Hola</h2>")
            .contains("Cuerpo")
            .contains("Montserrat, 'Segoe UI', sans-serif");
    }

    @Test
    @DisplayName("render(): sin CTA no hay botón; pie por defecto")
    void noCtaDefaultFooter() {
        String html = base();
        assertThat(html)
            .doesNotContain("bgcolor=\"#B3252B\"")
            .contains("bgcolor=\"#F8F2E6\"")
            .contains("border-top:1px solid #D8CDB6")
            .contains("padding:18px 32px")
            .contains("Familia Mazzariello · Desde 2015");
    }

    @Test
    @DisplayName("render(): con CTA dibuja el botón con tabla y escapa la URL en el atributo")
    void withCta() {
        String html = EmailLayout.render(EmailContent.of("T", "")
            .withCta("Ver menú", "https://x.test/a?b=1&c=\"2\""));
        assertThat(html)
            .contains("bgcolor=\"#B3252B\"")
            .contains("display:inline-block; background:#B3252B; color:#ffffff; font-weight:700; font-size:13px; text-transform:uppercase; letter-spacing:1px; text-decoration:none; padding:14px 24px; border-radius:6px; line-height:18px;")
            .contains("href=\"https://x.test/a?b=1&amp;c=&quot;2&quot;\"")
            .contains(">Ver menú</a>")
            .doesNotContain("copiá este link");
    }

    @Test
    @DisplayName("render(): pie personalizado y HTML bajo el pie")
    void customFooterAndBelow() {
        String html = EmailLayout.render(EmailContent.of("T", "")
            .withFooter("Enviado desde la web de Arias")
            .withBelowFooter("<a href=\"https://u.test\">Baja</a>"));
        assertThat(html)
            .contains("Enviado desde la web de Arias")
            .doesNotContain("Familia Mazzariello")
            .contains("<a href=\"https://u.test\">Baja</a>");
        assertThat(html.indexOf("Baja")).isGreaterThan(html.indexOf("Enviado desde la web de Arias"));
    }

    @Test
    @DisplayName("render(): el título se escapa")
    void titleEscaped() {
        String html = EmailLayout.render(EmailContent.of("<script>&", ""));
        assertThat(html).contains("&lt;script&gt;&amp;").doesNotContain("<script>");
    }

    @Test
    @DisplayName("escape(): conserva acentos y signos, escapa markup")
    void escapeKeepsAccents() {
        assertThat(EmailLayout.escape("¡Hola María! <b>")).isEqualTo("¡Hola María! &lt;b&gt;");
        assertThat(EmailLayout.escape(null)).isEmpty();
    }

    @Test
    @DisplayName("helpers: párrafos, caja rotulada, lista, tablas")
    void helpers() {
        assertThat(EmailLayout.paragraph("x"))
            .contains("font-size:15px").contains("line-height:1.6").contains("color:#1A110F").contains("margin:0 0 16px");
        assertThat(EmailLayout.mutedParagraph("x")).contains("#66524B");
        String box = EmailLayout.labelledBox("Tu pedido", EmailLayout.bulletList(List.of("A", "B")));
        assertThat(box).contains("#F8F2E6").contains("Tu pedido").contains("text-transform:uppercase")
            .contains("<li").contains(">A</li>").contains(">B</li>");
        String kv = EmailLayout.labelValueTable(List.of(Map.entry("Nombre", "Ana")));
        assertThat(kv).contains("Nombre").contains("Ana");
        String summary = EmailLayout.summaryTable(List.of("Retiro", "Pedidos"), List.of(List.of("11:00", "2")));
        assertThat(summary).contains("border-bottom:2px solid #DC2C2E").contains("border-bottom:1px solid #D8CDB6")
            .contains("Retiro").contains("11:00");
    }
}
