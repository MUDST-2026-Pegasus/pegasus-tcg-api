package com.pegasus.pegasustcgapi.service.setting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The full seed wipes platform_setting and writes back a fixed list, so a key that a
 * migration adds but the list lacks disappears after seeding and its readers fail.
 */
class FullSeedSettingsTest {

    @Test
    void theFullSeedRestoresEverySettingTheCodeReads() throws IOException, IllegalAccessException {
        String seed;
        try (InputStream in = getClass().getResourceAsStream("/db/full-seed/01_setup.sql")) {
            seed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<String> keys = new ArrayList<>();
        for (Field field : PlatformSettingService.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                keys.add((String) field.get(null));
            }
        }

        assertThat(keys).isNotEmpty();
        assertThat(keys).allSatisfy(key -> assertThat(seed).contains("('" + key + "'"));
    }
}
