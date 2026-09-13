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

    private Slugs() {
    }

    /** @return lower case, ASCII, words joined by single hyphens, at most {@code maxLength} long. */
    public static String slugify(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");

        return fit(folded, maxLength);
    }

    /**
     * The slug a caller asked for, or one built from the parts, with {@code -2},
     * {@code -3}… appended until nothing else holds it.
     *
     * <p>The result never exceeds {@code maxLength}, suffix included. The column
     * it lands in has a fixed width, and a name that fits on its own can stop
     * fitting the moment a second product with the same name needs {@code -2}.
     *
     * @param maxLength the width of the column the slug is stored in
     * @param taken     answers whether a slug is already in use
     */
    public static String unique(String preferred, int maxLength, Predicate<String> taken, String... parts) {
        String base = preferred == null || preferred.isBlank()
                ? slugify(String.join(" ", parts), maxLength)
                : slugify(preferred, maxLength);

        if (base.isEmpty()) {
            base = "item";
        }
        if (!taken.test(base)) {
            return base;
        }
        // Two cards can legitimately share a name; the suffix is what keeps both.
        for (int suffix = 2; suffix < 1000; suffix++) {
            String tail = "-" + suffix;
            String candidate = fit(base, maxLength - tail.length()) + tail;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free slug for " + base);
    }

    /** Cuts on a word boundary where there is one, so a shortened slug does not end mid-word. */
    private static String fit(String slug, int maxLength) {
        if (slug.length() <= maxLength) {
            return slug;
        }
        String cut = slug.substring(0, maxLength);
        int lastHyphen = cut.lastIndexOf('-');
        return lastHyphen > 0 ? cut.substring(0, lastHyphen) : cut;
    }
}
