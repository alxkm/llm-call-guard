package callguard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader, enough to decide whether a response is well formed and to look at a few keys. It keeps the
 * core free of a JSON library, so it can sit under any framework without a version conflict. Objects become
 * {@link Map}, arrays {@link List}, numbers {@link Double}, and {@code null} becomes {@link #NULL}.
 */
public final class Json {
    /** The JSON {@code null}, distinct from an absent key. */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** @throws IllegalArgumentException if {@code text} is not one complete JSON value */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    private Object value() {
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", NULL);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw error("expected a key");
            }
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = next();
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw error("expected , or }");
            }
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = next();
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw error("expected , or ]");
            }
        }
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw error("short unicode escape");
                    }
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw error("bad escape");
            }
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) {
            throw error("bad literal");
        }
        i += word.length();
        return v;
    }

    private Double number() {
        int start = i;
        if (peek() == '-') {
            i++;
        }
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw error("unexpected character");
        }
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        return s.charAt(i);
    }

    private char next() {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("expected " + c);
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at " + i);
    }
}
