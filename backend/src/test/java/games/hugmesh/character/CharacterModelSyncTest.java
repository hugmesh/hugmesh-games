package games.hugmesh.character;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The drift guard the consumer-copy contract rests on (decision 028).
 *
 * <p>This service carries a VERBATIM copy of the canonical character model in
 * {@code rpg-builder}; {@code make sync-character-model} refreshes it. Without
 * this test, the first canonical change after a sync silently forks the two —
 * two services rendering a different character from the same document, with
 * nothing failing anywhere.
 *
 * <p>Deliberately RECURSIVE and over EVERY regular file, not a flat listing of
 * {@code *.java}: an adversarial pass proved a flat compare stays green when a
 * fork hides in a subdirectory ({@code character/sub/Helper.java} compiles into
 * the same artifact) or in a non-Java resource. Relative paths are compared
 * both directions, so an ADDED file is drift exactly like a changed one.
 *
 * <p>This file itself is kept byte-identical across all consumer services —
 * one guard, not per-service variants that can themselves drift.
 */
class CharacterModelSyncTest {

    private static final Path CANONICAL = Paths.get(
            "..", "rpg-builder", "src", "main", "java", "games", "hugmesh", "character");

    private static final Path COPY = Paths.get(
            "src", "main", "java", "games", "hugmesh", "character");

    @Test
    void copyIsByteIdenticalToCanonicalTree() throws IOException {
        assertThat(Files.isDirectory(CANONICAL))
                .as("canonical character-model tree at %s — this test needs the full "
                        + "repo checkout (rpg-builder alongside this service); a missing "
                        + "canonical tree means drift CANNOT be checked, which is a "
                        + "failure, not a skip", CANONICAL.toAbsolutePath().normalize())
                .isTrue();
        assertThat(Files.isDirectory(COPY))
                .as("consumer copy at %s — run `make sync-character-model`",
                        COPY.toAbsolutePath().normalize())
                .isTrue();

        Set<String> canonicalFiles = relativeFilesIn(CANONICAL);
        Set<String> copyFiles = relativeFilesIn(COPY);

        // Both directions: a canonical file missing here is a stale copy; an
        // extra file here — at ANY depth — is a local fork of the package.
        assertThat(copyFiles)
                .as("file trees must match both directions — resync with "
                        + "`make sync-character-model` (never edit the copy)")
                .isEqualTo(canonicalFiles);
        assertThat(canonicalFiles).as("the canonical model exists").isNotEmpty();

        for (String rel : canonicalFiles) {
            byte[] canonical = Files.readAllBytes(CANONICAL.resolve(rel));
            byte[] copied = Files.readAllBytes(COPY.resolve(rel));
            assertThat(Arrays.equals(canonical, copied))
                    .as("%s differs from the canonical rpg-builder tree — the copy "
                            + "is verbatim-only; change the canonical file and run "
                            + "`make sync-character-model`", rel)
                    .isTrue();
        }
    }

    /** Every regular file under {@code dir}, as sorted relative path strings. */
    private static Set<String> relativeFilesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.walk(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
