package com.arias.printing;

import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishStation;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderItem;
import com.arias.users.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure renderer tests: no Spring, no sockets. */
class TicketRendererTest {

    private static final Charset CP850 = Charset.forName("IBM850");
    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;

    private final TicketRenderer renderer = new TicketRenderer();

    // -- builders -----------------------------------------------------------

    private static Order order(String notas, OrderItem... items) {
        Order o = Order.builder()
            .id(123L)
            .user(User.builder().nickname("Coty").email("coty@test.com").build())
            .fecha(LocalDate.of(2026, 3, 10))
            // 16:30Z = 13:30 in America/Argentina/Buenos_Aires
            .pickupAt(Instant.parse("2026-03-10T16:30:00Z"))
            .estado(OrderEstado.COMANDADO).creditTotal(items.length).notas(notas).build();
        for (OrderItem i : items) {
            o.addItem(i);
        }
        return o;
    }

    private static OrderItem item(String dish, String side, String notas, DishStation station) {
        return OrderItem.builder()
            .dish(Dish.builder().nombre(dish).station(station).build())
            .dishNombre(dish).dishCategoria("Premium").sideNombre(side).notas(notas).creditCost(1).build();
    }

    private static OrderItem item(String dish, String side) {
        return item(dish, side, null, DishStation.COCINA);
    }

    /** Decodes the ticket and strips the ESC/POS commands, leaving the printable lines. */
    private static List<String> lines(byte[] bytes) {
        String text = new String(bytes, CP850)
            .replaceAll("\u001B@", "")
            .replaceAll("\u001B[taEM\\-][\\s\\S]", "")
            .replaceAll("\u001D![\\s\\S]", "")
            .replaceAll("\u001DV[\\s\\S]{2}", "");
        return Arrays.asList(text.split("\n", -1));
    }

    private static boolean contains(byte[] haystack, byte... needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // -- printer state reset (printers are shared with Maxirest) ------------

    @Test
    @DisplayName("starts and ends with ESC @ so the printer is always left in its default state")
    void startsAndEndsWithReset() {
        byte[] b = renderer.render(order(null, item("Milanesa", null)), DishStation.COCINA);

        assertThat(b[0]).isEqualTo(ESC);
        assertThat(b[1]).isEqualTo((byte) 0x40);
        assertThat(b[b.length - 2]).isEqualTo(ESC);
        assertThat(b[b.length - 1]).isEqualTo((byte) 0x40);
    }

    @Test
    @DisplayName("the final ESC @ comes right after the cut command (GS V 66 0)")
    void resetFollowsCut() {
        byte[] b = renderer.render(order(null, item("Milanesa", null)), DishStation.COCINA);
        byte[] tail = Arrays.copyOfRange(b, b.length - 6, b.length);

        assertThat(tail).containsExactly(GS, (byte) 0x56, (byte) 66, (byte) 0, ESC, (byte) 0x40);
    }

    @Test
    @DisplayName("alignment, size and bold are restored to normal before the final reset")
    void stateRestoredBeforeFinalReset() {
        byte[] b = renderer.render(order(null, item("Milanesa", null)), DishStation.PARRILLA);

        assertThat(lastArg(b, ESC, (byte) 0x61)).isEqualTo(0);
        assertThat(lastArg(b, GS, (byte) 0x21)).isEqualTo(0);
        assertThat(lastArg(b, ESC, (byte) 0x45)).isEqualTo(0);
    }

    private static int lastArg(byte[] b, byte first, byte second) {
        for (int i = b.length - 3; i >= 0; i--) {
            if (b[i] == first && b[i + 1] == second) return b[i + 2];
        }
        throw new AssertionError("command not found");
    }

    @Test
    @DisplayName("selects code page 850 (ESC t 2) and Font A")
    void selectsCodePageAndFont() {
        byte[] b = renderer.render(order(null, item("Milanesa", null)), DishStation.COCINA);

        assertThat(contains(b, ESC, (byte) 0x74, (byte) 2)).isTrue();
        assertThat(contains(b, ESC, (byte) 0x4D, (byte) 0)).isTrue();
    }

    // -- content ------------------------------------------------------------

    @Test
    @DisplayName("header carries the app name with the station, order number, pickup time and customer")
    void headerContent() {
        List<String> cocina = lines(renderer.render(order(null, item("Milanesa", null)), DishStation.COCINA));
        List<String> parrilla = lines(renderer.render(order(null, item("Milanesa", null)), DishStation.PARRILLA));

        assertThat(cocina.get(0)).isEqualTo("APP ARIAS - COCINA");
        assertThat(parrilla.get(0)).isEqualTo("APP ARIAS - PARRILLA");
        assertThat(cocina).contains("PEDIDO N\u00B0 123", "RETIRA 13:30");
        assertThat(cocina.get(1)).isEqualTo("=".repeat(48));
        assertThat(cocina.get(4)).isEqualTo("=".repeat(48));
    }

    @Test
    @DisplayName("header station line is bold and centered; PEDIDO and RETIRA are double size")
    void headerStyles() {
        byte[] b = renderer.render(order(null, item("Milanesa", null)), DishStation.COCINA);
        String s = new String(b, CP850);

        assertThat(s).contains("\u001Ba\u0001\u001BE\u0001APP ARIAS - COCINA\n");
        assertThat(s).contains("\u001D!\u0011PEDIDO N\u00B0 123\n");
        assertThat(s).contains("\u001D!\u0011RETIRA 13:30\n");
    }

    @Test
    @DisplayName("customer name on the left and weekday + date right-aligned in 48 columns")
    void nameAndDateAligned() {
        Order o = order(null, item("Milanesa", null));
        List<String> l = lines(renderer.render(o, DishStation.COCINA));

        String expected = "Coty" + " ".repeat(48 - 4 - 14) + "Mar 10/03/2026";
        assertThat(l).contains(expected);
        assertThat(expected).hasSize(48);
    }

    @Test
    @DisplayName("when name and date do not fit, the date goes on its own line")
    void dateOnOwnLine() {
        Order o = order(null, item("Milanesa", null));
        o.getUser().setNickname("N".repeat(40));
        List<String> l = lines(renderer.render(o, DishStation.COCINA));

        int idx = l.indexOf("N".repeat(40));
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(l.get(idx + 1).strip()).isEqualTo("Mar 10/03/2026");
    }

    @Test
    @DisplayName("Comandado line shows the local time, and is omitted when null")
    void comandadoLine() {
        Order with = order(null, item("Milanesa", null));
        with.setComandadoAt(Instant.parse("2026-03-10T15:31:00Z"));
        Order without = order(null, item("Milanesa", null));

        assertThat(lines(renderer.render(with, DishStation.COCINA))).contains("Comandado 12:31");
        assertThat(lines(renderer.render(without, DishStation.COCINA))).noneMatch(s -> s.contains("Comandado"));
    }

    @Test
    @DisplayName("item lines are double height (GS ! 1), keeping 48 columns")
    void itemLinesDoubleHeight() {
        byte[] b = renderer.render(order(null, item("Milanesa", "Papas")), DishStation.COCINA);
        String s = new String(b, CP850);

        assertThat(s).contains("\u001D!\u00011x Milanesa\n");
        assertThat(s).doesNotContain("\u001D!\u00101x");
    }

    @Test
    @DisplayName("item groups are separated by a blank line and item notes are bold NOTA:")
    void groupSeparationAndItemNote() {
        Order o = order(null,
            item("Milanesa", "Papas", "Sin cebolla, porfa", DishStation.COCINA),
            item("Ravioles", null));
        byte[] b = renderer.render(o, DishStation.COCINA);
        List<String> l = lines(b);

        int idx = l.indexOf("1x Milanesa");
        assertThat(l.get(idx + 1)).isEqualTo("   1x C/Papas");
        assertThat(l.get(idx + 2)).isEqualTo("   NOTA: Sin cebolla, porfa");
        assertThat(l.get(idx + 3)).isEmpty();
        assertThat(l.get(idx + 4)).isEqualTo("1x Ravioles");
        assertThat(new String(b, CP850)).contains("\u001BE\u0001   NOTA: Sin cebolla, porfa\n");
    }

    @Test
    @DisplayName("order notes appear under a bold NOTA DEL PEDIDO heading, only when present")
    void orderNotesSection() {
        List<String> with = lines(renderer.render(order("Tocar timbre", item("Milanesa", null)), DishStation.COCINA));
        List<String> without = lines(renderer.render(order(null, item("Milanesa", null)), DishStation.COCINA));

        int idx = with.indexOf("NOTA DEL PEDIDO");
        assertThat(idx).isGreaterThan(0);
        assertThat(with.get(idx - 1)).isEqualTo("-".repeat(48));
        assertThat(with.get(idx + 1)).isEqualTo("Tocar timbre");
        assertThat(without).doesNotContain("NOTA DEL PEDIDO");
    }

    @Test
    @DisplayName("footer closes with = and the centered item count, singular and plural")
    void footerCount() {
        List<String> one = lines(renderer.render(order(null, item("Milanesa", null)), DishStation.COCINA));
        List<String> three = lines(renderer.render(order(null,
            item("Milanesa", null), item("Milanesa", null), item("Ravioles", null)), DishStation.COCINA));

        String f1 = "1 plato \u00B7 fin del pedido 123";
        String f3 = "3 platos \u00B7 fin del pedido 123";
        assertThat(one).contains(" ".repeat((48 - f1.length()) / 2) + f1);
        assertThat(three).contains(" ".repeat((48 - f3.length()) / 2) + f3);
        int i = three.indexOf(" ".repeat((48 - f3.length()) / 2) + f3);
        assertThat(three.get(i - 1)).isEqualTo("=".repeat(48));
    }

    @Test
    @DisplayName("N-degree, middle dot and accented weekdays are encoded in CP850")
    void cp850TicketBytes() {
        byte[] b = renderer.render(order(null, item("Milanesa", null)), DishStation.COCINA);
        assertThat(contains(b, "N".getBytes(CP850)[0], (byte) 0xF8, (byte) ' ')).isTrue(); // N\u00B0 (0xF8)
        assertThat(contains(b, (byte) 0xFA)).isTrue(); // middle dot

        Order wed = order(null, item("Milanesa", null)); // 2026-10-21 is a Wednesday
        wed.setFecha(LocalDate.of(2026, 10, 21));
        Order sat = order(null, item("Milanesa", null));
        sat.setFecha(LocalDate.of(2026, 10, 24));
        assertThat(contains(renderer.render(wed, DishStation.COCINA), (byte) 'M', (byte) 'i', (byte) 0x82)).isTrue();
        assertThat(contains(renderer.render(sat, DishStation.COCINA), (byte) 'S', (byte) 0xA0, (byte) 'b')).isTrue();
    }

    @Test
    @DisplayName("identical items are grouped: dish and side both carry the quantity")
    void groupsIdenticalItems() {
        Order o = order(null,
            item("Milanesa Napolitana", "Papas fritas"),
            item("Milanesa Napolitana", "Papas fritas"));

        List<String> l = lines(renderer.render(o, DishStation.COCINA));

        int idx = l.indexOf("2x Milanesa Napolitana");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(l.get(idx + 1)).isEqualTo("   2x C/Papas fritas");
    }

    @Test
    @DisplayName("a single item prints 1x")
    void singleItemPrintsOne() {
        List<String> l = lines(renderer.render(order(null, item("Ravioles", "Salsa fileto")), DishStation.COCINA));

        assertThat(l).contains("1x Ravioles", "   1x C/Salsa fileto");
    }

    @Test
    @DisplayName("an item without side prints no side line")
    void noSideLine() {
        List<String> l = lines(renderer.render(order(null, item("Ensalada", null)), DishStation.COCINA));

        assertThat(l).contains("1x Ensalada");
        assertThat(l).noneMatch(s -> s.contains("C/"));
    }

    @Test
    @DisplayName("same dish with different side or different note is NOT grouped")
    void differentSideOrNoteNotGrouped() {
        Order o = order(null,
            item("Milanesa", "Papas fritas"),
            item("Milanesa", "Puré"),
            item("Milanesa", "Papas fritas", "sin sal", DishStation.COCINA));

        List<String> l = lines(renderer.render(o, DishStation.COCINA));

        assertThat(l.stream().filter(s -> s.equals("1x Milanesa")).count()).isEqualTo(3);
        assertThat(l).contains("   1x C/Papas fritas", "   1x C/Puré");
        assertThat(l).anyMatch(s -> s.contains("sin sal"));
    }

    @Test
    @DisplayName("identical items keep their first-appearance order")
    void groupOrderIsStable() {
        Order o = order(null, item("B", null), item("A", null), item("B", null));

        List<String> l = lines(renderer.render(o, DishStation.COCINA));

        assertThat(l.indexOf("2x B")).isLessThan(l.indexOf("1x A"));
    }

    @Test
    @DisplayName("item notes and order notes are printed when present")
    void notesPrinted() {
        Order o = order("Sin cebolla en todo",
            item("Milanesa", null, "bien cocida", DishStation.COCINA));

        List<String> l = lines(renderer.render(o, DishStation.COCINA));

        assertThat(l).anyMatch(s -> s.contains("bien cocida"));
        assertThat(l).anyMatch(s -> s.contains("Sin cebolla en todo"));
    }

    @Test
    @DisplayName("the full order is printed on both tickets, only the header differs")
    void bothTicketsHaveFullOrder() {
        Order o = order(null,
            item("Bife", null, null, DishStation.PARRILLA),
            item("Fideos", null, null, DishStation.COCINA));

        List<String> cocina = lines(renderer.render(o, DishStation.COCINA));
        List<String> parrilla = lines(renderer.render(o, DishStation.PARRILLA));

        assertThat(cocina).contains("1x Bife", "1x Fideos");
        assertThat(parrilla).contains("1x Bife", "1x Fideos");
    }

    // -- encoding -----------------------------------------------------------

    @Test
    @DisplayName("accents and Ñ are encoded in CP850")
    void cp850Accents() {
        byte[] b = renderer.render(order(null, item("Ñoquis de champiñón y ají", null)), DishStation.COCINA);

        assertThat(contains(b, (byte) 0xA5)).isTrue();   // Ñ
        assertThat(contains(b, (byte) 0xA4)).isTrue();   // ñ
        assertThat(contains(b, (byte) 0xA1)).isTrue();   // í
        assertThat(lines(b)).contains("1x Ñoquis de champiñón y ají");
    }

    @Test
    @DisplayName("characters outside CP850 become a question mark and never throw")
    void unencodableBecomesQuestionMark() {
        byte[] b = renderer.render(order("Gracias 😀 日本", item("Pollo 🍗", null)), DishStation.COCINA);

        List<String> l = lines(b);
        assertThat(l).contains("1x Pollo ?");
        assertThat(l).anyMatch(s -> s.contains("Gracias ? ??"));
    }

    // -- wrapping -----------------------------------------------------------

    @Test
    @DisplayName("long lines wrap at 48 columns without cutting words")
    void wrapsAtWordBoundaries() {
        String longName = "Milanesa napolitana con jamon y queso gratinado extra grande de la casa";
        List<String> l = lines(renderer.render(order(null, item(longName, null)), DishStation.COCINA));

        assertThat(l).allMatch(s -> s.length() <= 48);
        assertThat(l).contains("1x Milanesa napolitana con jamon y queso");
        assertThat(l).contains("   gratinado extra grande de la casa");
    }

    @Test
    @DisplayName("a word longer than the line is split")
    void splitsOverlongWord() {
        String word = "x".repeat(100);
        List<String> l = lines(renderer.render(order(word, item("Milanesa", null)), DishStation.COCINA));

        assertThat(l).allMatch(s -> s.length() <= 48);
        assertThat(String.join("", l)).contains(word);
    }
}
