package io.portable.identity.events;

import java.util.Collection;
import java.util.Map;

final class Json {
    private Json() {}

    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof String text) return quote(text);
        if (value instanceof Map<?, ?> map) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() == null) continue;
                if (!first) result.append(',');
                first = false;
                result.append(quote(entry.getKey().toString())).append(':').append(encode(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (Object item : collection) {
                if (!first) result.append(',');
                first = false;
                result.append(encode(item));
            }
            return result.append(']').toString();
        }
        throw new IllegalArgumentException("unsupported JSON value type");
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}
