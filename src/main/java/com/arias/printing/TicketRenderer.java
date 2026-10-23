package com.arias.printing;

import com.arias.catalog.dishes.DishStation;
import com.arias.orders.Order;
import com.arias.orders.OrderItem;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the ESC/POS bytes of a kitchen ticket ("comanda"), variant "A Clasica": 80 mm paper,
 * 48 columns, Font A, code page 850. Pure: no I/O, no sockets.
 *
 * <p>The printers are shared with another POS (Maxirest) that sends plain text and never resets
 * the printer, so every job starts AND ends with {@code ESC @}. Alignment, size and bold are put
 * back to normal before the cut and the final reset.
 */
@Component
public class TicketRenderer {

    static final int COLUMNS = 48;
    private static final Charset CP850 = Charset.forName("IBM850");
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String INDENT = "   ";
    private static final String SEPARATOR = "-".repeat(COLUMNS);
    private static final String HEAVY = "=".repeat(COLUMNS);
    /** Double-width characters take two cells, so a double-size line holds half the columns. */
    private static final int DOUBLE_COLUMNS = COLUMNS / 2;
    private static final String[] WEEKDAYS = {"Lun", "Mar", "Mi\u00E9", "Jue", "Vie", "S\u00E1b", "Dom"};

    // ESC/POS commands
    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;
    private static final byte[] INIT = {ESC, 0x40};
    private static final byte[] CODE_PAGE_850 = {ESC, 0x74, 2};
    private static final byte[] FONT_A = {ESC, 0x4D, 0};
    private static final byte[] ALIGN_LEFT = {ESC, 0x61, 0};
    private static final byte[] ALIGN_CENTER = {ESC, 0x61, 1};
    private static final byte[] BOLD_ON = {ESC, 0x45, 1};
    private static final byte[] BOLD_OFF = {ESC, 0x45, 0};
    private static final byte[] SIZE_DOUBLE = {GS, 0x21, 0x11};
    private static final byte[] SIZE_HEIGHT = {GS, 0x21, 0x01};
    private static final byte[] SIZE_NORMAL = {GS, 0x21, 0x00};
    private static final byte[] CUT = {GS, 0x56, 66, 0};
    private static final int FEED_LINES = 4;

    public byte[] render(Order order, DishStation station) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);

        write(out, INIT);
        write(out, CODE_PAGE_850);
        write(out, FONT_A);

        // Header: app + station (bold, centered), then the big PEDIDO / RETIRA lines.
        write(out, ALIGN_CENTER);
        write(out, BOLD_ON);
        line(out, "APP ARIAS - " + station.name());
        write(out, BOLD_OFF);
        write(out, ALIGN_LEFT);
        line(out, HEAVY);
        sized(out, SIZE_DOUBLE, DOUBLE_COLUMNS, "PEDIDO N\u00B0 " + order.getId());
        sized(out, SIZE_DOUBLE, DOUBLE_COLUMNS,
            "RETIRA " + TIME.format(order.getPickupAt().atZone(ZONE)));
        line(out, HEAVY);

        nameAndDate(out, order.getUser().displayName(), order.getFecha());
        if (order.getComandadoAt() != null) {
            line(out, "Comandado " + TIME.format(order.getComandadoAt().atZone(ZONE)));
        }
        line(out, SEPARATOR);

        List<Group> groups = group(order.getItems());
        int count = 0;
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            count += g.quantity;
            if (i > 0) {
                line(out, "");
            }
            write(out, SIZE_HEIGHT);
            wrapped(out, g.quantity + "x ", INDENT, g.dish);
            write(out, SIZE_NORMAL);
            if (g.side != null) {
                wrapped(out, INDENT + g.quantity + "x C/", INDENT + "     ", g.side);
            }
            if (g.note != null) {
                write(out, BOLD_ON);
                wrapped(out, INDENT + "NOTA: ", INDENT + "      ", g.note);
                write(out, BOLD_OFF);
            }
        }

        String orderNotes = clean(order.getNotas());
        if (!orderNotes.isEmpty()) {
            line(out, SEPARATOR);
            write(out, BOLD_ON);
            line(out, "NOTA DEL PEDIDO");
            write(out, BOLD_OFF);
            wrapped(out, "", "", orderNotes);
        }
        line(out, HEAVY);
        String footer = count + (count == 1 ? " plato" : " platos")
            + " \u00B7 fin del pedido " + order.getId();
        line(out, " ".repeat(Math.max(0, (COLUMNS - footer.length()) / 2)) + footer);

        // Back to the default state, feed, cut, and reset the printer for the next (Maxirest) job.
        write(out, ALIGN_LEFT);
        write(out, SIZE_NORMAL);
        write(out, BOLD_OFF);
        for (int i = 0; i < FEED_LINES; i++) {
            out.write('\n');
        }
        write(out, CUT);
        write(out, INIT);
        return out.toByteArray();
    }

    /** Customer name on the left, weekday + date right-aligned; the date drops to its own line if needed. */
    private void nameAndDate(ByteArrayOutputStream out, String name, LocalDate fecha) {
        String date = WEEKDAYS[fecha.getDayOfWeek().getValue() - 1] + " " + DATE.format(fecha);
        String cleanName = clean(name).replaceAll("\s+", " ");
        if (cleanName.length() + 1 + date.length() <= COLUMNS) {
            line(out, cleanName + " ".repeat(COLUMNS - cleanName.length() - date.length()) + date);
        } else {
            wrapped(out, "", "", cleanName);
            line(out, " ".repeat(COLUMNS - date.length()) + date);
        }
    }

    /** One line in a double-size mode, truncated to the narrower column count. */
    private void sized(ByteArrayOutputStream out, byte[] size, int columns, String text) {
        String t = clean(text);
        write(out, size);
        line(out, t.length() > columns ? t.substring(0, columns) : t);
        write(out, SIZE_NORMAL);
    }

    // -- grouping ---------------------------------------------------------

    private record Key(String dish, String side, String note) {}

    private static final class Group {
        final String dish;
        final String side;
        final String note;
        int quantity;

        Group(Key key) {
            this.dish = key.dish();
            this.side = key.side();
            this.note = key.note();
        }
    }

    /** Groups identical items (dish + side + item note), keeping first-appearance order. */
    private static List<Group> group(List<OrderItem> items) {
        Map<Key, Group> groups = new LinkedHashMap<>();
        for (OrderItem item : items) {
            Key key = new Key(
                Objects.requireNonNullElse(clean(item.getDishNombre()), ""),
                nullIfEmpty(clean(item.getSideNombre())),
                nullIfEmpty(clean(item.getNotas())));
            groups.computeIfAbsent(key, Group::new).quantity++;
        }
        return new ArrayList<>(groups.values());
    }

    // -- text helpers -----------------------------------------------------

    /**
     * Replaces control characters with spaces (keeping line breaks as '\n') and every character
     * that CP850 cannot encode with '?', one '?' per code point. Never throws.
     */
    static String clean(String text) {
        return sanitize(text).strip();
    }

    private static String sanitize(String text) {
        if (text == null) return "";
        CharsetEncoder encoder = CP850.newEncoder();
        StringBuilder sb = new StringBuilder(text.length());
        text.replace("\r\n", "\n").replace('\r', '\n').codePoints().forEach(cp -> {
            if (cp == '\n') {
                sb.append('\n');
            } else if (cp < 0x20 || cp == 0x7F) {
                sb.append(' ');
            } else if (Character.isBmpCodePoint(cp) && encoder.canEncode((char) cp)) {
                sb.appendCodePoint(cp);
            } else {
                sb.append('?');
            }
        });
        return sb.toString();
    }

    private static String nullIfEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * Writes {@code text} word-wrapped at {@link #COLUMNS}: the first line starts with {@code
     * firstPrefix}, the following ones with {@code restPrefix}. Words are never cut unless a single
     * word is longer than the available width.
     */
    private void wrapped(ByteArrayOutputStream out, String firstPrefix, String restPrefix, String text) {
        for (String paragraph : clean(text).split("\n", -1)) {
            String prefix = firstPrefix;
            StringBuilder current = new StringBuilder();
            boolean emitted = false;
            for (String word : paragraph.split(" +")) {
                if (word.isEmpty()) continue;
                int width = COLUMNS - prefix.length();
                while (word.length() > width) {
                    if (current.length() > 0) {
                        line(out, prefix + current);
                        current.setLength(0);
                        prefix = restPrefix;
                        width = COLUMNS - prefix.length();
                        emitted = true;
                        continue;
                    }
                    line(out, prefix + word.substring(0, width));
                    word = word.substring(width);
                    prefix = restPrefix;
                    width = COLUMNS - prefix.length();
                    emitted = true;
                }
                if (current.length() == 0) {
                    current.append(word);
                } else if (current.length() + 1 + word.length() <= width) {
                    current.append(' ').append(word);
                } else {
                    line(out, prefix + current);
                    prefix = restPrefix;
                    current.setLength(0);
                    current.append(word);
                    emitted = true;
                }
            }
            if (current.length() > 0 || !emitted) {
                line(out, prefix + current);
            }
        }
    }

    private void line(ByteArrayOutputStream out, String text) {
        byte[] bytes = sanitize(text).getBytes(CP850);
        out.write(bytes, 0, bytes.length);
        out.write('\n');
    }

    private static void write(ByteArrayOutputStream out, byte[] command) {
        out.write(command, 0, command.length);
    }
}
