package games.hugmesh.rpgbuilder.controller;

import games.hugmesh.rpgbuilder.entity.RpgAsset;
import games.hugmesh.rpgbuilder.security.Roles;
import games.hugmesh.rpgbuilder.service.AssetStorageService;
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
@RequestMapping("/api/rpg")
@PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
public class AssetController {

    private final AssetStorageService storage;

    public AssetController(AssetStorageService storage) { this.storage = storage; }

    @PostMapping(path = "/projects/{pid}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgAsset> upload(@PathVariable UUID pid,
                                 @RequestPart("slug") String slug,
                                 @RequestPart("kind") String kind,
                                 @RequestPart("file") FilePart file,
                                 Authentication auth) {
        return storage.upload(pid, slug, kind, file, Roles.principalOf(auth));
    }

    /**
     * Generate house-style placeholder art server-side and persist it as a
     * real asset row — same {@code ADMIN/PUBLISHER} + project-ownership gate as
     * {@link #upload}, same storage path, same 201 response shape. Lets a fresh
     * project get art without a hand-upload.
     */
    @PostMapping(path = "/projects/{pid}/assets/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgAsset> generate(@PathVariable UUID pid,
                                   @RequestBody GenerateRequest body,
                                   Authentication auth) {
        return storage.generate(pid, body.slug(), body.kind(), body.theme(), Roles.principalOf(auth));
    }

    /** Body for {@link #generate}: {@code { "slug", "kind", "theme"? }}. */
    public record GenerateRequest(String slug, String kind, String theme) {}

    @GetMapping("/projects/{pid}/assets")
    public Flux<RpgAsset> list(@PathVariable UUID pid, Authentication auth) {
        return storage.list(pid, Roles.principalOf(auth));
    }

    @GetMapping("/projects/{pid}/assets/{aid}")
    public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID pid, @PathVariable UUID aid,
                                                 Authentication auth) {
        return storage.read(pid, aid, Roles.principalOf(auth))
                .map(bytes -> ResponseEntity.ok()
                        .header("Cache-Control", "no-cache")
                        .body(bytes));
    }
}
