package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.ChapterAsset;
import games.snapdragon.bff.repository.ChapterAssetRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Registers and lists per-level custom assets (textures, plugins, meshes, sounds).
 * Each asset has a stable `label` that GDScript can use to look it up regardless of
 * filename. The actual bytes live on the classpath under
 * {@code assets/levels/{slug}/} and are served by {@link AssetController}.
 *
 * <p>Workflow for a Godot developer adding a new texture:
 * <ol>
 *   <li>Drop the file into {@code backend/src/main/resources/assets/levels/{slug}/}</li>
 *   <li>POST {@code /api/chapters/{id}/assets} with label + file_path + type</li>
 *   <li>From GDScript: fetch {@code /api/chapters/{id}/assets} and resolve by label</li>
 * </ol>
 */
@Tag(name = "Chapter Assets",
     description = "Registry for user-added Godot assets (textures, plugins, sounds) attached to a specific chapter. Both /api/chapters/{id}/assets and the legacy /api/levels/{id}/assets URLs work.")
@RestController
@RequestMapping({ "/api/chapters/{chapterId}/assets", "/api/levels/{chapterId}/assets" })
public class ChapterAssetController {

    private final ChapterAssetRepository repo;

    public ChapterAssetController(ChapterAssetRepository repo) { this.repo = repo; }

    public record RegisterAssetRequest(
            String label,
            String assetType,
            String filePath,
            String contentType,
            Long sizeBytes
    ) {}

    @Operation(
        summary = "List all assets registered for a level",
        description = """
            Returns every row in level_assets for the level. GDScript can iterate
            this list at load time to find textures/plugins by label without
            hardcoding file names.

            ```
            ──request──► assetRepo.findByChapterId(id)
             ──────────────●──●──●──|
                           ChapterAsset{id, label, assetType, filePath, …}…
            ```
            """)
    @GetMapping
    public Flux<ChapterAsset> list(@PathVariable Long chapterId) {
        return repo.findByChapterId(chapterId);
    }

    @Operation(summary = "Look up a single asset by label (e.g. 'oak-bark-texture')")
    @GetMapping("/by-label/{label}")
    public Mono<ChapterAsset> byLabel(@PathVariable Long chapterId, @PathVariable String label) {
        return repo.findByChapterIdAndLabel(chapterId, label)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @Operation(
        summary = "Register a new asset for a level",
        description = """
            Records metadata for an asset whose bytes already live on the
            classpath. The Godot dev drops the file into
            `backend/src/main/resources/assets/levels/{slug}/` then POSTs here
            with the label they want GDScript to use.

            ```
            ──request──► ChapterAsset{chapterId, label, assetType, filePath, …}
                          ──●──► repo.save()
             ──────────────────────●─|  ChapterAsset (with id, uploadedAt)
            ```
            """)
    @PostMapping
    public Mono<ChapterAsset> register(@PathVariable Long chapterId, @RequestBody RegisterAssetRequest req) {
        ChapterAsset a = new ChapterAsset();
        a.setChapterId(chapterId);
        a.setLabel(req.label());
        a.setAssetType(req.assetType() == null ? "texture" : req.assetType());
        a.setFilePath(req.filePath());
        a.setContentType(req.contentType());
        a.setSizeBytes(req.sizeBytes());
        return repo.save(a);
    }

    @Operation(summary = "Remove an asset registration")
    @DeleteMapping("/{assetId}")
    public Mono<Void> delete(@PathVariable Long chapterId, @PathVariable Long assetId) {
        return repo.deleteById(assetId);
    }
}
