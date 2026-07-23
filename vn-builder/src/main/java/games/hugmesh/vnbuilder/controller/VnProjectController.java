package games.hugmesh.vnbuilder.controller;

import games.hugmesh.vnbuilder.entity.*;
import games.hugmesh.vnbuilder.security.Roles;
import games.hugmesh.vnbuilder.service.VnProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST surface for the visual-novel maker. Mirrors the rpg-builder
 * shape so the frontend has symmetric clients.
 */
@RestController
@RequestMapping("/api/vn")
@PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
public class VnProjectController {

    private final VnProjectService svc;

    public VnProjectController(VnProjectService svc) { this.svc = svc; }

    @GetMapping("/projects")
    public Flux<VnProject> list(Authentication a) { return svc.listMine(Roles.principalOf(a)); }

    @GetMapping("/projects/{id}")
    public Mono<VnProject> get(@PathVariable UUID id, Authentication a) { return svc.get(id, Roles.principalOf(a)); }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VnProject> create(@RequestBody VnProject in, Authentication a) {
        return svc.create(in, Roles.principalOf(a));
    }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id, Authentication a) { return svc.delete(id, Roles.principalOf(a)); }

    // Acts (Chapters)
    @GetMapping("/projects/{pid}/acts")
    public Flux<VnAct> listActs(@PathVariable UUID pid, Authentication a) { return svc.listActs(pid, Roles.principalOf(a)); }
    @PostMapping("/projects/{pid}/acts")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VnAct> createAct(@PathVariable UUID pid, @RequestBody VnAct in, Authentication a) {
        return svc.createAct(pid, in, Roles.principalOf(a));
    }

    // Characters
    @GetMapping("/projects/{pid}/characters")
    public Flux<VnCharacter> listCharacters(@PathVariable UUID pid, Authentication a) { return svc.listCharacters(pid, Roles.principalOf(a)); }
    @PostMapping("/projects/{pid}/characters")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VnCharacter> createCharacter(@PathVariable UUID pid, @RequestBody VnCharacter in, Authentication a) {
        return svc.createCharacter(pid, in, Roles.principalOf(a));
    }

    // Backgrounds
    @GetMapping("/projects/{pid}/backgrounds")
    public Flux<VnBackground> listBackgrounds(@PathVariable UUID pid, Authentication a) { return svc.listBackgrounds(pid, Roles.principalOf(a)); }
    @PostMapping("/projects/{pid}/backgrounds")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VnBackground> createBackground(@PathVariable UUID pid, @RequestBody VnBackground in, Authentication a) {
        return svc.createBackground(pid, in, Roles.principalOf(a));
    }

    // Scenes
    @GetMapping("/projects/{pid}/scenes")
    public Flux<VnScene> listScenes(@PathVariable UUID pid, Authentication a) { return svc.listScenes(pid, Roles.principalOf(a)); }
    @PostMapping("/projects/{pid}/scenes")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VnScene> createScene(@PathVariable UUID pid, @RequestBody VnScene in, Authentication a) {
        return svc.createScene(pid, in, Roles.principalOf(a));
    }

    // Lines (per scene)
    @GetMapping("/projects/{pid}/scenes/{sid}/lines")
    public Flux<VnLine> listLines(@PathVariable UUID pid, @PathVariable UUID sid, Authentication a) {
        return svc.listLines(pid, sid, Roles.principalOf(a));
    }
    @PostMapping("/projects/{pid}/scenes/{sid}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VnLine> createLine(@PathVariable UUID pid, @PathVariable UUID sid, @RequestBody VnLine in, Authentication a) {
        return svc.createLine(pid, sid, in, Roles.principalOf(a));
    }

    // Choices (per line)
    @GetMapping("/projects/{pid}/lines/{lid}/choices")
    public Flux<VnChoice> listChoices(@PathVariable UUID pid, @PathVariable UUID lid, Authentication a) {
        return svc.listChoices(pid, lid, Roles.principalOf(a));
    }
    @PostMapping("/projects/{pid}/lines/{lid}/choices")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VnChoice> createChoice(@PathVariable UUID pid, @PathVariable UUID lid, @RequestBody VnChoice in, Authentication a) {
        return svc.createChoice(pid, lid, in, Roles.principalOf(a));
    }
}
