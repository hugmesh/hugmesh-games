package games.hugmesh.bff.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the filesystem implementation. The pre-strategy
 * GameUploadService also has its own test suite that exercises the
 * end-to-end flow through this strategy; this class drills into the
 * strategy contract directly so a regression at the storage boundary
 * shows up with a clear failure rather than as a mysterious upload
 * integration miss.
 */
class FilesystemUploadStorageTest {

    @Test
    void installFromStaging_movesContentAndReturnsPrefixedLocator(@TempDir Path tmp) throws Exception {
        Path gamesDir = tmp.resolve("games");
        Files.createDirectories(gamesDir);
        UploadStorage storage = new FilesystemUploadStorage(gamesDir);

        Path staging = stageFiles(tmp.resolve("staging"),
                "index.html", "<html>hello</html>",
                "bundle/asset.bin", "AAAA");

        String locator = storage.installFromStaging("my-slug", "abc123", staging);

        assertThat(locator).startsWith("file:");
        assertThat(locator).contains("my-slug/abc123");
        Path versionDir = gamesDir.resolve("my-slug").resolve("abc123");
        assertThat(Files.isRegularFile(versionDir.resolve("index.html"))).isTrue();
        assertThat(Files.isRegularFile(versionDir.resolve("bundle/asset.bin"))).isTrue();
        // Staging dir should be gone after the move.
        assertThat(Files.exists(staging)).isFalse();
    }

    @Test
    void read_resolvesRelativePathUnderLocator(@TempDir Path tmp) throws Exception {
        Path gamesDir = tmp.resolve("games");
        Files.createDirectories(gamesDir);
        UploadStorage storage = new FilesystemUploadStorage(gamesDir);
        Path staging = stageFiles(tmp.resolve("staging"),
                "index.html", "<html>hello</html>");
        String locator = storage.installFromStaging("my-slug", "abc123", staging);

        Resource res = storage.read(locator, "index.html");

        assertThat(res).isNotNull();
        assertThat(res.exists()).isTrue();
        try (var in = res.getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("<html>hello</html>");
        }
    }

    @Test
    void read_returnsNullOnZipSlipAttempt(@TempDir Path tmp) throws Exception {
        Path gamesDir = tmp.resolve("games");
        Files.createDirectories(gamesDir);
        UploadStorage storage = new FilesystemUploadStorage(gamesDir);
        Path staging = stageFiles(tmp.resolve("staging"), "index.html", "ok");
        String locator = storage.installFromStaging("my-slug", "abc123", staging);

        // Attempt to escape the version dir via '..' segments.
        Resource res = storage.read(locator, "../../../etc/passwd");

        assertThat(res).isNull();
    }

    @Test
    void deleteVersion_removesEntireSubtree(@TempDir Path tmp) throws Exception {
        Path gamesDir = tmp.resolve("games");
        Files.createDirectories(gamesDir);
        UploadStorage storage = new FilesystemUploadStorage(gamesDir);
        Path staging = stageFiles(tmp.resolve("staging"),
                "index.html", "ok",
                "bundle/asset.bin", "AAAA");
        String locator = storage.installFromStaging("my-slug", "abc123", staging);
        Path versionDir = gamesDir.resolve("my-slug").resolve("abc123");
        assertThat(Files.exists(versionDir)).isTrue();

        storage.deleteVersion(locator);

        assertThat(Files.exists(versionDir)).isFalse();
    }

    @Test
    void read_acceptsLegacyRawAbsolutePathLocator(@TempDir Path tmp) throws Exception {
        // Rows written before the prefix scheme stored raw absolute
        // paths. The filesystem strategy must keep resolving them.
        Path gamesDir = tmp.resolve("games");
        Files.createDirectories(gamesDir);
        UploadStorage storage = new FilesystemUploadStorage(gamesDir);
        Path versionDir = gamesDir.resolve("legacy-slug").resolve("def456");
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("index.html"), "legacy");

        Resource res = storage.read(versionDir.toString(), "index.html");

        assertThat(res).isNotNull();
        assertThat(res.exists()).isTrue();
    }

    @Test
    void label_isStableForLogScrapers() {
        assertThat(new FilesystemUploadStorage(Path.of("/tmp")).label()).isEqualTo("filesystem");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static Path stageFiles(Path root, String... pathContentPairs) throws IOException {
        Files.createDirectories(root);
        for (int i = 0; i < pathContentPairs.length; i += 2) {
            Path target = root.resolve(pathContentPairs[i]);
            Files.createDirectories(target.getParent());
            Files.writeString(target, pathContentPairs[i + 1]);
        }
        return root;
    }
}
