package com.pegasus.pegasustcgapi.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Evidence for DEF-755-05: fields a journey saw as null that /v1/api-docs does not declare
 * nullable. Written to build/tcg755/undocumented-nulls.txt (ids in paths folded to {id}).
 */
final class UndocumentedNulls {

    private static final Set<String> SEEN = new ConcurrentSkipListSet<>();
    private static final Path OUT = Path.of("build", "tcg755", "undocumented-nulls.txt");

    private UndocumentedNulls() {
    }

    static synchronized void record(String violation) {
        if (SEEN.add(violation.replaceAll("/\\d+(?=[/ ])", "/{id}").replaceAll("/\\d+/", "/{n}/"))) {
            try {
                Files.createDirectories(OUT.getParent());
                Files.write(OUT, SEEN);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
