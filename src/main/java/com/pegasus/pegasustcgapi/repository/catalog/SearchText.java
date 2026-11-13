package com.pegasus.pegasustcgapi.repository.catalog;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The Java side of {@code f_search_norm} (migration V20261112_1000): the query is
 * folded by the same rules as the text it is matched against. Keep the two in step.
 */
public final class SearchText {

    private static final String QWERTY = "`1234567890-=qwertyuiop[]\\asdfghjkl;'zxcvbnm,./";
    private static final String KEDMANEE = "_ๅ/-ภถุึคตจขชๆไำพะัีรนยบลฃฟหกดเ้่าสวงผปแอิืทมใฝ";

    private SearchText() {
    }

    public static String normalize(String text) {
        String folded = Normalizer.normalize(text.replaceAll("[่้๊๋็์]", ""), Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f]", "")
                .toLowerCase(Locale.ROOT)
                .replace("#", "")
                .replace('-', '/')
                .replaceAll("(^|[^0-9])0+([0-9])", "$1$2");
        return folded.replaceAll("\\s+", " ").trim();
    }

    /** Fuzzy matching on two letters or on bare numbers finds nonsense, so only words of three or more. */
    public static boolean fuzzyEligible(String normalized) {
        return normalized.length() >= 3 && normalized.codePoints().anyMatch(Character::isLetter);
    }

    /**
     * The same keys read on the other layout (Thai Kedmanee ⇄ QWERTY), or null when
     * some character is on neither, so it was not typed with the wrong layout.
     */
    public static String switchKeyboard(String text) {
        String typed = text.toLowerCase(Locale.ROOT).trim();
        boolean fromThai = typed.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.THAI);
        String from = fromThai ? KEDMANEE : QWERTY;
        String to = fromThai ? QWERTY : KEDMANEE;
        StringBuilder switched = new StringBuilder();
        for (char c : typed.toCharArray()) {
            int key = from.indexOf(c);
            if (Character.isWhitespace(c)) {
                switched.append(' ');
            } else if (key >= 0) {
                switched.append(to.charAt(key));
            } else {
                return null;
            }
        }
        return switched.isEmpty() ? null : switched.toString();
    }
}
