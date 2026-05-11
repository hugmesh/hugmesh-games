package games.snapdragon.vnbuilder.service;

import games.snapdragon.vnbuilder.entity.*;
import games.snapdragon.vnbuilder.repository.*;
import games.snapdragon.vnbuilder.util.SlugUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class VnProjectService {

    private final VnProjectRepository projects;
    private final VnActRepository acts;
    private final VnCharacterRepository characters;
    private final VnBackgroundRepository backgrounds;
    private final VnSceneRepository scenes;
    private final VnLineRepository lines;
    private final VnChoiceRepository choices;

    public VnProjectService(VnProjectRepository projects, VnActRepository acts, VnCharacterRepository characters,
                            VnBackgroundRepository backgrounds, VnSceneRepository scenes,
                            VnLineRepository lines, VnChoiceRepository choices) {
        this.projects = projects; this.acts = acts; this.characters = characters;
        this.backgrounds = backgrounds; this.scenes = scenes; this.lines = lines; this.choices = choices;
    }

    public Flux<VnProject> listMine(String principal) {
        // Composite "sub|username" — query both halves and merge so a
        // dev-seeded project (owned by username) and a runtime-created
        // project (owned by sub) both appear under the same caller.
        if (principal == null || principal.isEmpty()) return Flux.empty();
        int bar = principal.indexOf('|');
        String sub = bar < 0 ? principal : principal.substring(0, bar);
        String username = bar < 0 ? "" : principal.substring(bar + 1);
        Flux<VnProject> bySub = sub.isEmpty()
                ? Flux.empty()
                : projects.findByOwnerCasdoorIdOrderByUpdatedAtDesc(sub);
        Flux<VnProject> byName = (username.isEmpty() || username.equals(sub))
                ? Flux.empty()
                : projects.findByOwnerCasdoorIdOrderByUpdatedAtDesc(username);
        return Flux.concat(bySub, byName).distinct(VnProject::getId);
    }

    public Mono<VnProject> get(UUID id, String principal) {
        return projects.findById(id).flatMap(p -> ownsOrFail(p, principal));
    }

    public Mono<VnProject> create(VnProject in, String principal) {
        if (!SlugUtil.isValid(in.getSlug())) return Mono.error(bad("slug must match ^[a-z0-9][a-z0-9-]{0,63}$"));
        if (in.getDisplayName() == null || in.getDisplayName().isBlank()) return Mono.error(bad("displayName is required"));
        // Stamp the canonical Casdoor sub as the stored owner; the
        // composite principal accepts username as an alias for lookups
        // but new rows should always key on the more stable sub.
        in.setOwnerCasdoorId(games.snapdragon.vnbuilder.security.Roles.subFromPrincipal(principal));
        in.setCreatedAt(OffsetDateTime.now());
        in.setUpdatedAt(OffsetDateTime.now());
        return projects.save(in).onErrorMap(e -> new ResponseStatusException(HttpStatus.CONFLICT,
                "slug already taken: " + in.getSlug(), e));
    }

    public Mono<Void> delete(UUID id, String owner) {
        return get(id, owner).flatMap(projects::delete);
    }

    public Flux<VnAct> listActs(UUID pid, String owner) {
        return get(pid, owner).flatMapMany(p -> acts.findByProjectIdOrderBySortOrderAscNameAsc(pid));
    }

    public Mono<VnAct> createAct(UUID pid, VnAct in, String owner) {
        if (!SlugUtil.isValid(in.getSlug())) return Mono.error(bad("act slug invalid"));
        if (in.getName() == null || in.getName().isBlank()) return Mono.error(bad("act name required"));
        return get(pid, owner).flatMap(p -> { in.setProjectId(pid); return acts.save(in); });
    }

    public Flux<VnCharacter> listCharacters(UUID pid, String owner) {
        return get(pid, owner).flatMapMany(p -> characters.findByProjectIdOrderByDisplayNameAsc(pid));
    }

    public Mono<VnCharacter> createCharacter(UUID pid, VnCharacter in, String owner) {
        if (!SlugUtil.isValid(in.getSlug())) return Mono.error(bad("character slug invalid"));
        if (in.getDisplayName() == null || in.getDisplayName().isBlank()) return Mono.error(bad("character displayName required"));
        return get(pid, owner).flatMap(p -> { in.setProjectId(pid); return characters.save(in); });
    }

    public Flux<VnBackground> listBackgrounds(UUID pid, String owner) {
        return get(pid, owner).flatMapMany(p -> backgrounds.findByProjectIdOrderByNameAsc(pid));
    }

    public Mono<VnBackground> createBackground(UUID pid, VnBackground in, String owner) {
        if (!SlugUtil.isValid(in.getSlug())) return Mono.error(bad("background slug invalid"));
        if (in.getName() == null || in.getName().isBlank()) return Mono.error(bad("background name required"));
        return get(pid, owner).flatMap(p -> { in.setProjectId(pid); return backgrounds.save(in); });
    }

    public Flux<VnScene> listScenes(UUID pid, String owner) {
        return get(pid, owner).flatMapMany(p -> scenes.findByProjectIdOrderBySortOrderAscNameAsc(pid));
    }

    public Mono<VnScene> createScene(UUID pid, VnScene in, String owner) {
        if (!SlugUtil.isValid(in.getSlug())) return Mono.error(bad("scene slug invalid"));
        if (in.getName() == null || in.getName().isBlank()) return Mono.error(bad("scene name required"));
        if (in.getActId() == null) return Mono.error(bad("scene must reference an act"));
        return get(pid, owner).flatMap(p -> { in.setProjectId(pid); return scenes.save(in); });
    }

    public Flux<VnLine> listLines(UUID pid, UUID sceneId, String owner) {
        return get(pid, owner).flatMapMany(p -> lines.findBySceneIdOrderByLineIndexAsc(sceneId));
    }

    public Mono<VnLine> createLine(UUID pid, UUID sceneId, VnLine in, String owner) {
        if (in.getText() == null) return Mono.error(bad("line text required"));
        return get(pid, owner).flatMap(p -> { in.setSceneId(sceneId); return lines.save(in); });
    }

    public Flux<VnChoice> listChoices(UUID pid, UUID lineId, String owner) {
        return get(pid, owner).flatMapMany(p -> choices.findByLineIdOrderBySortOrderAsc(lineId));
    }

    public Mono<VnChoice> createChoice(UUID pid, UUID lineId, VnChoice in, String owner) {
        if (in.getLabel() == null || in.getLabel().isBlank()) return Mono.error(bad("choice label required"));
        if (in.getTargetSceneId() == null) return Mono.error(bad("choice targetSceneId required"));
        return get(pid, owner).flatMap(p -> { in.setLineId(lineId); return choices.save(in); });
    }

    private Mono<VnProject> ownsOrFail(VnProject p, String principal) {
        if (p == null) return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found"));
        // Composite principal accepts either the JWT sub or the JWT
        // name as the project's stored owner (dev fixtures key on
        // name, runtime-created rows key on sub).
        if (!games.snapdragon.vnbuilder.security.Roles.principalMatches(principal, p.getOwnerCasdoorId())) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "project owned by another user"));
        }
        return Mono.just(p);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
