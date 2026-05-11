package games.snapdragon.rpgbuilder.controller;

import games.snapdragon.rpgbuilder.entity.RpgAsset;
import games.snapdragon.rpgbuilder.security.Roles;
import games.snapdragon.rpgbuilder.service.AssetStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
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

    @GetMapping("/projects/{pid}/assets/{aid}")
    public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID pid, @PathVariable UUID aid,
                                                 Authentication auth) {
        return storage.read(pid, aid, Roles.principalOf(auth))
                .map(bytes -> ResponseEntity.ok()
                        .header("Cache-Control", "no-cache")
                        .body(bytes));
    }
}
