package games.hugmesh.rpgbuilder.controller;

import games.hugmesh.rpgbuilder.entity.*;
import games.hugmesh.rpgbuilder.security.Roles;
import games.hugmesh.rpgbuilder.service.RpgProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * One controller class hosting every CRUD path under /api/rpg/. Two
 * roles allowed: ROLE_ADMIN (can manage anything) and ROLE_PUBLISHER
 * (can manage their own projects). The service layer enforces
 * per-project ownership against the JWT subject — non-admin callers
 * get 403 on someone else's project.
 *
 * Note: ROLE_USER on its own can't reach any of these routes — the
 * RPG maker is a publisher tool. Per-game scope (which BFF
 * publisher_game_scopes covers) is enforced LATER, at upload time,
 * when the build worker forwards the user's bearer to the BFF.
 */
@RestController
@RequestMapping("/api/rpg")
@PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
public class RpgProjectController {

    private final RpgProjectService projects;

    public RpgProjectController(RpgProjectService projects) { this.projects = projects; }

    @GetMapping("/projects")
    public Flux<RpgProject> list(Authentication auth) {
        return projects.listMine(Roles.principalOf(auth));
    }

    @GetMapping("/projects/{id}")
    public Mono<RpgProject> get(@PathVariable UUID id, Authentication auth) {
        return projects.get(id, Roles.principalOf(auth));
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgProject> create(@RequestBody RpgProject in, Authentication auth) {
        return projects.create(in, Roles.principalOf(auth));
    }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id, Authentication auth) {
        return projects.delete(id, Roles.principalOf(auth));
    }

    // ── Areas ───────────────────────────────────────────────────────────────

    @GetMapping("/projects/{pid}/areas")
    public Flux<RpgArea> listAreas(@PathVariable UUID pid, Authentication auth) {
        return projects.listAreas(pid, Roles.principalOf(auth));
    }

    @PostMapping("/projects/{pid}/areas")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgArea> createArea(@PathVariable UUID pid, @RequestBody RpgArea in, Authentication auth) {
        return projects.createArea(pid, in, Roles.principalOf(auth));
    }

    // ── Rooms ───────────────────────────────────────────────────────────────

    @GetMapping("/projects/{pid}/rooms")
    public Flux<RpgRoom> listRooms(@PathVariable UUID pid, Authentication auth) {
        return projects.listRooms(pid, Roles.principalOf(auth));
    }

    @PostMapping("/projects/{pid}/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgRoom> createRoom(@PathVariable UUID pid, @RequestBody RpgRoom in, Authentication auth) {
        return projects.createRoom(pid, in, Roles.principalOf(auth));
    }

    @PutMapping("/projects/{pid}/rooms/{rid}")
    public Mono<RpgRoom> updateRoom(@PathVariable UUID pid, @PathVariable UUID rid,
                                    @RequestBody RpgRoom in, Authentication auth) {
        return projects.updateRoom(pid, rid, in, Roles.principalOf(auth));
    }

    @DeleteMapping("/projects/{pid}/rooms/{rid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteRoom(@PathVariable UUID pid, @PathVariable UUID rid, Authentication auth) {
        return projects.deleteRoom(pid, rid, Roles.principalOf(auth));
    }

    // ── Exits ───────────────────────────────────────────────────────────────

    @GetMapping("/projects/{pid}/exits")
    public Flux<RpgExit> listExits(@PathVariable UUID pid, Authentication auth) {
        return projects.listExits(pid, Roles.principalOf(auth));
    }

    @PostMapping("/projects/{pid}/exits")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgExit> createExit(@PathVariable UUID pid, @RequestBody RpgExit in, Authentication auth) {
        return projects.createExit(pid, in, Roles.principalOf(auth));
    }

    @DeleteMapping("/projects/{pid}/exits/{exid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteExit(@PathVariable UUID pid, @PathVariable UUID exid, Authentication auth) {
        return projects.deleteExit(pid, exid, Roles.principalOf(auth));
    }

    // ── Items ───────────────────────────────────────────────────────────────

    @GetMapping("/projects/{pid}/items")
    public Flux<RpgItem> listItems(@PathVariable UUID pid, Authentication auth) {
        return projects.listItems(pid, Roles.principalOf(auth));
    }

    @PostMapping("/projects/{pid}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgItem> createItem(@PathVariable UUID pid, @RequestBody RpgItem in, Authentication auth) {
        return projects.createItem(pid, in, Roles.principalOf(auth));
    }

    // ── NPCs ────────────────────────────────────────────────────────────────

    @GetMapping("/projects/{pid}/npcs")
    public Flux<RpgNpc> listNpcs(@PathVariable UUID pid, Authentication auth) {
        return projects.listNpcs(pid, Roles.principalOf(auth));
    }

    @PostMapping("/projects/{pid}/npcs")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgNpc> createNpc(@PathVariable UUID pid, @RequestBody RpgNpc in, Authentication auth) {
        return projects.createNpc(pid, in, Roles.principalOf(auth));
    }

    // ── Scripts ─────────────────────────────────────────────────────────────

    @GetMapping("/projects/{pid}/scripts")
    public Flux<RpgScript> listScripts(@PathVariable UUID pid, Authentication auth) {
        return projects.listScripts(pid, Roles.principalOf(auth));
    }

    @PostMapping("/projects/{pid}/scripts")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RpgScript> createScript(@PathVariable UUID pid, @RequestBody RpgScript in, Authentication auth) {
        return projects.createScript(pid, in, Roles.principalOf(auth));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
