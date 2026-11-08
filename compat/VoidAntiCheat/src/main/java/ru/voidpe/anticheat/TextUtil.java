package ru.voidpe.anticheat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Цвета: &-коды, &#RRGGBB, градиенты. */
public final class TextUtil {

    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Pattern GRADIENT =
            Pattern.compile("<gradient:#([0-9A-Fa-f]{6}):#([0-9A-Fa-f]{6})>(.*?)</gradient>", Pattern.DOTALL);

    private TextUtil() {
    }

    public static String color(String text) {
        if (text == null) {
            return "";
        }
        String out = applyGradients(text);
        Matcher m = HEX.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder rep = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                rep.append('§').append(c);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return sb.toString().replace('&', '§');
    }

    private static String applyGradients(String text) {
        Matcher m = GRADIENT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int[] from = parse(m.group(1));
            int[] to = parse(m.group(2));
            String body = m.group(3);
            StringBuilder grad = new StringBuilder();
            int len = Math.max(1, body.length());
            for (int i = 0; i < body.length(); i++) {
                double t = len == 1 ? 0 : (double) i / (len - 1);
                grad.append(String.format("&#%02X%02X%02X",
                        (int) Math.round(from[0] + (to[0] - from[0]) * t),
                        (int) Math.round(from[1] + (to[1] - from[1]) * t),
                        (int) Math.round(from[2] + (to[2] - from[2]) * t))).append(body.charAt(i));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(grad.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static int[] parse(String hex) {
        return new int[]{
                Integer.parseInt(hex.substring(0, 2), 16),
                Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)
        };
    }
}
