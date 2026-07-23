package games.hugmesh.vnbuilder.controller;

import games.hugmesh.vnbuilder.entity.VnAsset;
import games.hugmesh.vnbuilder.security.Roles;
import games.hugmesh.vnbuilder.service.AssetStorageService;
import games.hugmesh.vnbuilder.service.ProceduralAssetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/vn")
@PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
public class AssetController {

    private final AssetStorageService storage;
    private final ProceduralAssetService procedural;

    public AssetController(AssetStorageService storage, ProceduralAssetService procedural) {
        this.storage = storage;
        this.procedural = procedural;
    }

    @PostMapping(path = "/projects/{pid}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VnAsset> upload(@PathVariable UUID pid,
                                @RequestPart("slug") String slug,
                                @RequestPart("kind") String kind,
                                @RequestPart("file") FilePart file,
                                Authentication a) {
        return storage.upload(pid, slug, kind, file, Roles.principalOf(a));
    }

    /** The project's assets, newest-first — feeds the maker's asset gallery
     *  and the background/portrait pickers. */
    @GetMapping("/projects/{pid}/assets")
    public Flux<VnAsset> list(@PathVariable UUID pid, Authentication a) {
        return storage.list(pid, Roles.principalOf(a));
    }

    /**
     * Generate house-style placeholder art and persist it as a real
     * {@link VnAsset} (mime image/svg+xml) through the same storage layout an
     * upload uses, so it materializes + renders identically. Same authz +
     * project-ownership guard as {@link #upload}.
     */
    @PostMapping(path = "/projects/{pid}/assets/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VnAsset> generate(@PathVariable UUID pid,
                                  @RequestBody GenerateAssetRequest req,
                                  Authentication a) {
        byte[] svg = procedural.generate(req.kind(), req.slug(), req.theme());
        return storage.storeGenerated(pid, req.slug(), req.kind(),
                ProceduralAssetService.SVG_MIME, svg, Roles.principalOf(a));
    }

    @GetMapping("/projects/{pid}/assets/{aid}")
    public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID pid, @PathVariable UUID aid, Authentication a) {
        return storage.read(pid, aid, Roles.principalOf(a))
                .map(bytes -> ResponseEntity.ok().header("Cache-Control", "no-cache").body(bytes));
    }

    /** Body for {@code POST /projects/{pid}/assets/generate}. */
    public record GenerateAssetRequest(String slug, String kind, String theme) {}
}
