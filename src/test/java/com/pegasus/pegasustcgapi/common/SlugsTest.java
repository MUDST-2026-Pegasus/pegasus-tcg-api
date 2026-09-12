package com.pegasus.pegasustcgapi.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlugsTest {

    @Test
    @DisplayName("a name becomes a URL segment")
    void slugifiesPlainNames() {
        assertThat(Slugs.slugify("Pikachu ex")).isEqualTo("pikachu-ex");
        assertThat(Slugs.slugify("  Terastal   Festival  ")).isEqualTo("terastal-festival");
        assertThat(Slugs.slugify("Charizard VMAX (Rainbow Rare)")).isEqualTo("charizard-vmax-rainbow-rare");
    }

    @Test
    @DisplayName("accents fold to their letter instead of vanishing")
    void foldsAccents() {
        assertThat(Slugs.slugify("Pokémon")).isEqualTo("pokemon");
    }

    @Test
    @DisplayName("a name with no ASCII left is not silently empty")
    void thaiOnlyNameFallsBack() {
        assertThat(Slugs.slugify("โปเกมอนการ์ด")).isEmpty();
        assertThat(Slugs.unique(null, taken -> false, "โปเกมอนการ์ด")).isEqualTo("item");
    }

    @Test
    @DisplayName("a clash picks up a suffix rather than failing")
    void appendsSuffixUntilFree() {
        Set<String> used = Set.of("pikachu-ex", "pikachu-ex-2");

        assertThat(Slugs.unique(null, used::contains, "Pikachu ex")).isEqualTo("pikachu-ex-3");
    }

    @Test
    @DisplayName("an explicit slug wins over the generated one, still folded")
    void preferredSlugIsUsed() {
        assertThat(Slugs.unique("Pikachu EX SV8a", taken -> false, "ignored"))
                .isEqualTo("pikachu-ex-sv8a");
    }

    @Test
    @DisplayName("a very long name is cut on a word boundary")
    void truncatesOnWordBoundary() {
        String slug = Slugs.slugify(("word ".repeat(40)).trim());

        assertThat(slug).hasSizeLessThanOrEqualTo(120);
        assertThat(slug).doesNotEndWith("-");
        assertThat(slug).endsWith("word");
    }
}
