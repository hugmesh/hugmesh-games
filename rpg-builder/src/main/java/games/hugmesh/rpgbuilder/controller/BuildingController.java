package games.hugmesh.rpgbuilder.controller;

import games.hugmesh.rpgbuilder.entity.RpgBuilding;
import games.hugmesh.rpgbuilder.entity.RpgShopStock;
import games.hugmesh.rpgbuilder.security.Roles;
import games.hugmesh.rpgbuilder.service.RpgBuildingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Town buildings + shop stock under /api/rpg — same role gate and
 * ownership funnel as {@link RpgProjectController}: ADMIN or PUBLISHER
 * at the route, per-project owner check (ownsOrFail via
 * RpgProjectService.get) inside the service.
 *
 * PUT is deliberately narrow: name / kind / grid move only.
 * floors+basements (and slug/host/area) are immutable after create —
 * the service 400s any change attempt.
 */
@RestController
@RequestMapping("/api/rpg")
@PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
public class BuildingController {

    private final RpgBuildingService buildings;

    public BuildingController(RpgBuildingService buildings) { this.buildings = buildings; }

    @GetMapping("/projects/{pid}/buildings")
    public Flux<RpgBuilding> list(@PathVariable UUID pid, Authentication auth) {
        return buildings.list(pid, Roles.principalOf(auth));
    }

    @PostMapping("/projects/{pid}/buildings")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgBuilding> create(@PathVariable UUID pid, @RequestBody RpgBuilding in, Authentication auth) {
        return buildings.create(pid, in, Roles.principalOf(auth));
    }

    @PutMapping("/projects/{pid}/buildings/{bid}")
    public Mono<RpgBuilding> update(@PathVariable UUID pid, @PathVariable UUID bid,
                                    @RequestBody RpgBuilding in, Authentication auth) {
        return buildings.update(pid, bid, in, Roles.principalOf(auth));
    }

    @DeleteMapping("/projects/{pid}/buildings/{bid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID pid, @PathVariable UUID bid, Authentication auth) {
        return buildings.delete(pid, bid, Roles.principalOf(auth));
    }

    // ── Shop stock ──────────────────────────────────────────────────────────

    @GetMapping("/projects/{pid}/buildings/{bid}/stock")
    public Flux<RpgShopStock> listStock(@PathVariable UUID pid, @PathVariable UUID bid, Authentication auth) {
        return buildings.listStock(pid, bid, Roles.principalOf(auth));
    }

    @PostMapping("/projects/{pid}/buildings/{bid}/stock")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgShopStock> createStock(@PathVariable UUID pid, @PathVariable UUID bid,
                                          @RequestBody RpgShopStock in, Authentication auth) {
        return buildings.createStock(pid, bid, in, Roles.principalOf(auth));
    }

    @DeleteMapping("/projects/{pid}/buildings/{bid}/stock/{sid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteStock(@PathVariable UUID pid, @PathVariable UUID bid, @PathVariable UUID sid,
                                  Authentication auth) {
        return buildings.deleteStock(pid, bid, sid, Roles.principalOf(auth));
    }

    // Reactive return type required — see RpgProjectController#badArg.
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<String>> badArg(IllegalArgumentException e) {
        return Mono.just(ResponseEntity.badRequest().body(e.getMessage()));
    }
}
