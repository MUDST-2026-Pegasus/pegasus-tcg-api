package com.pegasus.pegasustcgapi.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlugsTest {

    private static final int WIDTH = 120;

    @Test
    @DisplayName("a name becomes a URL segment")
    void slugifiesPlainNames() {
        assertThat(Slugs.slugify("Pikachu ex", WIDTH)).isEqualTo("pikachu-ex");
        assertThat(Slugs.slugify("  Terastal   Festival  ", WIDTH)).isEqualTo("terastal-festival");
        assertThat(Slugs.slugify("Charizard VMAX (Rainbow Rare)", WIDTH)).isEqualTo("charizard-vmax-rainbow-rare");
    }

    @Test
    @DisplayName("accents fold to their letter instead of vanishing")
    void foldsAccents() {
        assertThat(Slugs.slugify("Pokémon", WIDTH)).isEqualTo("pokemon");
    }

    @Test
    @DisplayName("a name with no ASCII left is not silently empty")
    void thaiOnlyNameFallsBack() {
        assertThat(Slugs.slugify("โปเกมอนการ์ด", WIDTH)).isEmpty();
        assertThat(Slugs.unique(null, WIDTH, taken -> false, "โปเกมอนการ์ด")).isEqualTo("item");
    }

    @Test
    @DisplayName("a clash picks up a suffix rather than failing")
    void appendsSuffixUntilFree() {
        Set<String> used = Set.of("pikachu-ex", "pikachu-ex-2");

        assertThat(Slugs.unique(null, WIDTH, used::contains, "Pikachu ex")).isEqualTo("pikachu-ex-3");
    }

    @Test
    @DisplayName("an explicit slug wins over the generated one, still folded")
    void preferredSlugIsUsed() {
        assertThat(Slugs.unique("Pikachu EX SV8a", WIDTH, taken -> false, "ignored"))
                .isEqualTo("pikachu-ex-sv8a");
    }

    @Test
    @DisplayName("a very long name is cut on a word boundary")
    void truncatesOnWordBoundary() {
        String slug = Slugs.slugify(("word ".repeat(40)).trim(), WIDTH);

        assertThat(slug).hasSizeLessThanOrEqualTo(WIDTH);
        assertThat(slug).doesNotEndWith("-");
        assertThat(slug).endsWith("word");
    }

    @Test
    @DisplayName("a name that fills the column still fits once it needs a suffix")
    void suffixNeverOverflowsTheColumn() {
        String fullWidth = "a".repeat(100);

        String second = Slugs.unique(null, 100, fullWidth::equals, fullWidth);

        // A 100-char game name used to become a 102-char slug and a 500 from the database.
        assertThat(second).hasSizeLessThanOrEqualTo(100).endsWith("-2");
    }

    @Test
    @DisplayName("a narrow column, such as a SKU, is respected even for a long code")
    void narrowColumnIsRespected() {
        String sku = Slugs.unique(null, 64, taken -> false,
                "POKEMON", "SV8A", "123456789012345678901234567890AB", "ENGLISHXX", "REVERSE_HOLO",
                "FIRST_EDITION", "Special illustration rare stamped promo");

        assertThat(sku).hasSizeLessThanOrEqualTo(64).doesNotEndWith("-");
    }
}
