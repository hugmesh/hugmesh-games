package games.hugmesh.rpgbuilder.service;

import games.hugmesh.rpgbuilder.entity.*;
import games.hugmesh.rpgbuilder.repository.*;
import games.hugmesh.rpgbuilder.util.SlugUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Project + sub-entity CRUD with slug-shape validation, ownership
 * enforcement, and the implicit invariants Mudlet's editor relies on
 * (no orphan exits, unique slugs per project). Every write returns
 * the persisted row so the frontend can immediately render it.
 */
@Service
public class RpgProjectService {

    private final RpgProjectRepository projects;
    private final RpgAreaRepository areas;
    private final RpgRoomRepository rooms;
    private final RpgExitRepository exits;
    private final RpgItemRepository items;
    private final RpgNpcRepository npcs;
    private final RpgScriptRepository scripts;

    public RpgProjectService(RpgProjectRepository projects, RpgAreaRepository areas, RpgRoomRepository rooms,
                             RpgExitRepository exits, RpgItemRepository items, RpgNpcRepository npcs,
                             RpgScriptRepository scripts) {
        this.projects = projects;
        this.areas = areas;
        this.rooms = rooms;
        this.exits = exits;
        this.items = items;
        this.npcs = npcs;
        this.scripts = scripts;
    }

    public Flux<RpgProject> listMine(String principal) {
        // Composite "sub|username" — query both forms and merge. Dev
        // fixtures own rows via username; runtime-created rows own via
        // sub. Same JWT principal sees both in one list.
        if (principal == null || principal.isEmpty()) return Flux.empty();
        int bar = principal.indexOf('|');
        String sub = bar < 0 ? principal : principal.substring(0, bar);
        String username = bar < 0 ? "" : principal.substring(bar + 1);
        Flux<RpgProject> bySub = sub.isEmpty()
                ? Flux.empty()
                : projects.findByOwnerCasdoorIdOrderByUpdatedAtDesc(sub);
        Flux<RpgProject> byName = (username.isEmpty() || username.equals(sub))
                ? Flux.empty()
                : projects.findByOwnerCasdoorIdOrderByUpdatedAtDesc(username);
        return Flux.concat(bySub, byName).distinct(RpgProject::getId);
    }

    public Mono<RpgProject> get(UUID id, String ownerCasdoorId) {
        return projects.findById(id).flatMap(p -> ownsOrFail(p, ownerCasdoorId));
    }

    public Mono<RpgProject> create(RpgProject in, String ownerCasdoorId) {
        if (!SlugUtil.isValid(in.getSlug())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "slug must match ^[a-z0-9][a-z0-9-]{0,63}$"));
        }
        if (in.getDisplayName() == null || in.getDisplayName().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "displayName is required"));
        }
        // Composite principal — store only the canonical sub so the
        // owner column stays comparable to other audit rows that key
        // on casdoor sub. Lookups still accept either half via
        // Roles.principalMatches in ownsOrFail below.
        in.setOwnerCasdoorId(games.hugmesh.rpgbuilder.security.Roles.subFromPrincipal(ownerCasdoorId));
        in.setCreatedAt(OffsetDateTime.now());
        in.setUpdatedAt(OffsetDateTime.now());
        return projects.save(in)
                .onErrorMap(e -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "slug already taken: " + in.getSlug(), e));
    }

    public Mono<Void> delete(UUID id, String ownerCasdoorId) {
        return get(id, ownerCasdoorId).flatMap(projects::delete);
    }

    public Flux<RpgArea> listAreas(UUID projectId, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).flatMapMany(p -> areas.findByProjectIdOrderBySortOrderAscNameAsc(projectId));
    }

    public Mono<RpgArea> createArea(UUID projectId, RpgArea in, String ownerCasdoorId) {
        if (!SlugUtil.isValid(in.getSlug())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "area slug invalid"));
        }
        if (in.getName() == null || in.getName().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "area name required"));
        }
        return get(projectId, ownerCasdoorId).flatMap(p -> {
            in.setProjectId(projectId);
            return areas.save(in);
        });
    }

    public Flux<RpgRoom> listRooms(UUID projectId, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).flatMapMany(p -> rooms.findByProjectIdOrderByNameAsc(projectId));
    }

    public Mono<RpgRoom> createRoom(UUID projectId, RpgRoom in, String ownerCasdoorId) {
        if (!SlugUtil.isValid(in.getSlug())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "room slug invalid"));
        }
        if (in.getName() == null || in.getName().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "room name required"));
        }
        if (in.getAreaId() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "room must reference an area"));
        }
        return get(projectId, ownerCasdoorId).flatMap(p -> {
            in.setProjectId(projectId);
            return rooms.save(in);
        });
    }

    public Mono<RpgRoom> updateRoom(UUID projectId, UUID roomId, RpgRoom in, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).flatMap(p ->
                rooms.findById(roomId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "room not found")))
                        .flatMap(existing -> {
                            existing.setName(in.getName());
                            existing.setDescription(in.getDescription());
                            existing.setEnvironment(in.getEnvironment());
                            existing.setX(in.getX()); existing.setY(in.getY()); existing.setZ(in.getZ());
                            existing.setIconAssetId(in.getIconAssetId());
                            existing.setGdScript(in.getGdScript());
                            return rooms.save(existing);
                        }));
    }

    public Mono<Void> deleteRoom(UUID projectId, UUID roomId, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).flatMap(p -> rooms.deleteById(roomId));
    }

    public Mono<RpgExit> createExit(UUID projectId, RpgExit in, String ownerCasdoorId) {
        if (in.getDirection() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction required"));
        }
        if (in.getFromRoomId() == null || in.getToRoomId() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromRoomId and toRoomId required"));
        }
        return get(projectId, ownerCasdoorId).flatMap(p -> {
            in.setProjectId(projectId);
            return exits.save(in)
                    .onErrorMap(e -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "direction already taken on this room: " + in.getDirection(), e));
        });
    }

    public Mono<Void> deleteExit(UUID projectId, UUID exitId, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).flatMap(p -> exits.deleteById(exitId));
    }

    public Flux<RpgExit> listExits(UUID projectId, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).flatMapMany(p -> exits.findByProjectId(projectId));
    }

    public Flux<RpgItem> listItems(UUID projectId, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).flatMapMany(p -> items.findByProjectIdOrderByNameAsc(projectId));
    }

    public Mono<RpgItem> createItem(UUID projectId, RpgItem in, String ownerCasdoorId) {
        if (!SlugUtil.isValid(in.getSlug())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "item slug invalid"));
        }
        if (in.getName() == null || in.getName().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "item name required"));
        }
        return get(projectId, ownerCasdoorId).flatMap(p -> { in.setProjectId(projectId); return items.save(in); });
    }

    public Flux<RpgNpc> listNpcs(UUID projectId, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).flatMapMany(p -> npcs.findByProjectIdOrderByNameAsc(projectId));
    }

    public Mono<RpgNpc> createNpc(UUID projectId, RpgNpc in, String ownerCasdoorId) {
        if (!SlugUtil.isValid(in.getSlug())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "npc slug invalid"));
        }
        if (in.getName() == null || in.getName().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "npc name required"));
        }
        return get(projectId, ownerCasdoorId).flatMap(p -> { in.setProjectId(projectId); return npcs.save(in); });
    }

    public Flux<RpgScript> listScripts(UUID projectId, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).flatMapMany(p -> scripts.findByProjectIdOrderByKindAscNameAsc(projectId));
    }

    public Mono<RpgScript> createScript(UUID projectId, RpgScript in, String ownerCasdoorId) {
        if (!SlugUtil.isValid(in.getSlug())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "script slug invalid"));
        }
        if (in.getName() == null || in.getName().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "script name required"));
        }
        if (in.getKind() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "script kind required"));
        }
        return get(projectId, ownerCasdoorId).flatMap(p -> { in.setProjectId(projectId); return scripts.save(in); });
    }

    private Mono<RpgProject> ownsOrFail(RpgProject p, String principal) {
        if (p == null) return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found"));
        // Composite principal accepts either the JWT sub or the JWT
        // name as the project's stored owner. Dev fixtures seeded with
        // the username thus survive a fresh Casdoor user-id mint.
        if (!games.hugmesh.rpgbuilder.security.Roles.principalMatches(principal, p.getOwnerCasdoorId())) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "project owned by another user"));
        }
        return Mono.just(p);
    }
}
