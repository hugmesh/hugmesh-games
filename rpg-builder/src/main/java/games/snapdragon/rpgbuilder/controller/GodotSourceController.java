package games.snapdragon.rpgbuilder.controller;

import games.snapdragon.rpgbuilder.build.RpgGodotProjectRenderer;
import games.snapdragon.rpgbuilder.entity.RpgProject;
import games.snapdragon.rpgbuilder.security.Roles;
import games.snapdragon.rpgbuilder.service.RpgProjectService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Lets a publisher download the Godot project source the renderer
 * would feed to the export pipeline. Useful when the publisher wants
 * to open the project in the Godot editor directly to tweak art /
 * fine-grained scene composition that the maker UI doesn't surface
 * yet, then re-upload through {@code /api/admin/games/upload}.
 *
 * <p>The zip excludes the WASM binary (snapdragon_bridge.wasm); the
 * publisher gets a placeholder {@code bin/README.md} explaining
 * where to drop the matching binary from the host repo. Keeping the
 * binary out of every download lightens the response and guarantees
 * the publisher always pulls a fresh extension build.
 */
@RestController
@RequestMapping("/api/rpg")
@PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
public class GodotSourceController {

    private final RpgProjectService projects;
    private final RpgGodotProjectRenderer renderer;
    private final Path tempRoot;

    public GodotSourceController(RpgProjectService projects,
                                 RpgGodotProjectRenderer renderer,
                                 @Value("${app.build.workdir}") String workdir) {
        this.projects = projects;
        this.renderer = renderer;
        this.tempRoot = Path.of(workdir).resolve("downloads");
    }

    @GetMapping("/projects/{pid}/godot-zip")
    public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID pid, Authentication auth) {
        String owner = Roles.principalOf(auth);
        return projects.get(pid, owner).flatMap(project ->
                Mono.fromCallable(() -> Files.createDirectories(tempRoot.resolve(pid.toString())))
                        .subscribeOn(Schedulers.boundedElastic())
                        .then(renderer.render(project, tempRoot.resolve(pid.toString())))
                        .flatMap(rendered -> Mono.fromCallable(() -> packageZip(rendered.godotSrcRoot(), project))
                                .subscribeOn(Schedulers.boundedElastic())));
    }

    private static ResponseEntity<byte[]> packageZip(Path srcRoot, RpgProject project) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            try (var walk = Files.walk(srcRoot)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    String rel = srcRoot.relativize(p).toString().replace('\\', '/');
                    try {
                        zos.putNextEntry(new ZipEntry(rel));
                        zos.write(Files.readAllBytes(p));
                        zos.closeEntry();
                    } catch (IOException io) { throw new RuntimeException(io); }
                });
            }
            zos.putNextEntry(new ZipEntry("bin/README.md"));
            zos.write(BIN_PLACEHOLDER.getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("README.md"));
            zos.write(buildReadme(project).getBytes());
            zos.closeEntry();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + project.getSlug() + "-godot.zip\"")
                .body(baos.toByteArray());
    }

    private static final String BIN_PLACEHOLDER = """
            # snapdragon_bridge native binaries

            The Rust GDExtension binaries are NOT bundled here. Build them from
            the host repo (`make build-rust`) and drop the matching file into
            this directory:

            * Linux  : `libsnapdragon_bridge.so`
            * Windows: `snapdragon_bridge.dll`
            * macOS  : `libsnapdragon_bridge.dylib`
            * Web    : `snapdragon_bridge.wasm`

            The `snapdragon_bridge.gdextension` descriptor at the project root
            already points at these paths.
            """;

    private static String buildReadme(RpgProject project) {
        return """
                # %s — Godot source

                Generated by snapdragon-rpg-builder for editing in Godot 4.

                ## Open in Godot

                1. `cd` into this directory.
                2. Run `godot --editor project.godot`.
                3. Wire any extension binaries you need into `bin/` (see
                   `bin/README.md`).
                4. Hit F5 / play.

                ## Re-upload after editing

                Use `make build-godot` (or run the Godot exporter directly
                with the bundled `export_presets.cfg`) to produce a Web
                bundle, then upload the resulting zip via the publisher
                console at `/publisher`.

                ## Multiplayer / view channel

                The scene tree includes a `SnapdragonMultiplayerLobby` node
                so any avatar you parent under `Wizards` automatically
                participates in the multiplayer session lobby + per-player
                view channel — same contract as the bundled games.
                """.formatted(project.getDisplayName());
    }
}
