package games.snapdragon.vnbuilder.controller;

import games.snapdragon.vnbuilder.entity.VnBuildJob;
import games.snapdragon.vnbuilder.security.Roles;
import games.snapdragon.vnbuilder.service.BuildJobService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/vn")
@PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
public class BuildJobController {

    private final BuildJobService builds;

    public BuildJobController(BuildJobService builds) { this.builds = builds; }

    @PostMapping("/projects/{pid}/build")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<VnBuildJob> enqueue(@PathVariable UUID pid, @RequestBody Map<String, String> body, Authentication a) {
        return builds.enqueue(pid, body.get("targetSlug"), Roles.principalOf(a), Roles.bearerOf(a));
    }

    @GetMapping("/projects/{pid}/builds")
    public Flux<VnBuildJob> list(@PathVariable UUID pid, Authentication a) {
        return builds.listForProject(pid, Roles.principalOf(a));
    }

    @GetMapping("/builds/{bid}")
    public Mono<VnBuildJob> get(@PathVariable UUID bid, Authentication a) {
        return builds.get(bid, Roles.principalOf(a));
    }
}
