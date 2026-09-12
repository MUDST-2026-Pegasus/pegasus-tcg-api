package com.pegasus.pegasustcgapi.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Turns a name into the URL segment that stands in for an id — {@code /cards/501}
 * says nothing, {@code /cards/pikachu-ex-sv8a-025} says what it is and survives
 * being pasted into a chat.
 *
 * <p>Accents are folded rather than dropped, so "Pokémon" becomes "pokemon" and
 * not "pokmon". A name with nothing left after folding, which any all-Thai name
 * is, falls back to the caller's stem so the slug is never empty.
 */
public final class Slugs {

    private static final int MAX_LENGTH = 120;

    private Slugs() {
    }

    /** @return lower case, ASCII, words joined by single hyphens. */
    public static String slugify(String text) {
        if (text == null) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");

        return folded.length() <= MAX_LENGTH ? folded : trimAtHyphen(folded);
    }

    /**
     * The slug a caller asked for, or one built from the parts, with {@code -2},
     * {@code -3}… appended until nothing else holds it.
     *
     * @param taken answers whether a slug is already in use
     */
    public static String unique(String preferred, Predicate<String> taken, String... parts) {
        String base = preferred == null || preferred.isBlank()
                ? slugify(String.join(" ", parts))
                : slugify(preferred);

        if (base.isEmpty()) {
            base = "item";
        }
        if (!taken.test(base)) {
            return base;
        }
        // Two cards can legitimately share a name; the suffix is what keeps both.
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + "-" + suffix;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free slug for " + base);
    }

    /** Cuts on a word boundary so a truncated slug does not end mid-word. */
    private static String trimAtHyphen(String slug) {
        String cut = slug.substring(0, MAX_LENGTH);
        int lastHyphen = cut.lastIndexOf('-');
        return lastHyphen > 0 ? cut.substring(0, lastHyphen) : cut;
    }
}
