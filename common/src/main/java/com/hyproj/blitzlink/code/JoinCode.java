package com.hyproj.blitzlink.code;

import java.util.Locale;
import java.util.Optional;

/**
 * Join codes: {@code MCJE-} + 4 characters from a 30-letter alphabet without 0 1 O I L U (≈810 000 codes).
 * Same rules as {@code blitzlink_core::relay::normalize_code} (native/blitzlink-core/src/relay.rs):
 * case, spaces and hyphens are ignored and the prefix may be left out.
 */
public final class JoinCode {
    public static final String PREFIX = "MCJE";
    public static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int LENGTH = 4;

    private JoinCode() {}

    /** {@code " mcje-7k3q "}, {@code "7k3q"}, {@code "MCJE7K3Q"} → {@code MCJE-7K3Q}; anything else → empty. */
    public static Optional<String> normalize(String input) {
        if (input == null) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isWhitespace(c) || c == '-') continue;
            sb.append(Character.toUpperCase(c));
        }
        String s = sb.toString();
        if (s.startsWith(PREFIX)) s = s.substring(PREFIX.length());
        if (s.length() != LENGTH) return Optional.empty();
        for (char c : s.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) return Optional.empty();
        }
        return Optional.of(PREFIX + "-" + s);
    }

    /**
     * Whether the text field should be treated as a code rather than a vanilla address. Only input that starts with the prefix counts, so a 4-letter host name is never mistaken.
     */
    public static Optional<String> fromUserInput(String input) {
        if (input == null) return Optional.empty();
        String t = input.strip().toUpperCase(Locale.ROOT);
        return t.startsWith(PREFIX) ? normalize(t) : Optional.empty();
    }
}
