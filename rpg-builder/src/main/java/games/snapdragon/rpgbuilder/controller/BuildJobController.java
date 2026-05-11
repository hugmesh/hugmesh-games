package games.snapdragon.rpgbuilder.controller;

import games.snapdragon.rpgbuilder.entity.RpgBuildJob;
import games.snapdragon.rpgbuilder.security.Roles;
import games.snapdragon.rpgbuilder.service.BuildJobService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rpg")
@PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
public class BuildJobController {

    private final BuildJobService builds;

    public BuildJobController(BuildJobService builds) { this.builds = builds; }

    /**
     * Enqueue a build for the given project. Body must carry the
     * {@code targetSlug} the resulting bundle will register under
     * (typically equals the project slug, but a publisher can rename
     * at build time to ship multiple variants).
     */
    @PostMapping("/projects/{pid}/build")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<RpgBuildJob> enqueue(@PathVariable UUID pid,
                                     @RequestBody Map<String, String> body,
                                     Authentication auth) {
        return builds.enqueue(pid, body.get("targetSlug"), Roles.principalOf(auth), Roles.bearerOf(auth));
    }

    @GetMapping("/projects/{pid}/builds")
    public Flux<RpgBuildJob> list(@PathVariable UUID pid, Authentication auth) {
        return builds.listForProject(pid, Roles.principalOf(auth));
    }

    @GetMapping("/builds/{bid}")
    public Mono<RpgBuildJob> get(@PathVariable UUID bid, Authentication auth) {
        return builds.get(bid, Roles.principalOf(auth));
    }
}
