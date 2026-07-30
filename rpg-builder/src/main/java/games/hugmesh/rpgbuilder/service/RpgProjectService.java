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
import java.util.Set;
import java.util.UUID;

/**
 * Project + sub-entity CRUD with slug-shape validation, ownership
 * enforcement, and the implicit invariants Mudlet's editor relies on
 * (no orphan exits, unique slugs per project). Every write returns
 * the persisted row so the frontend can immediately render it.
 */
@Service
public class RpgProjectService {

    /** Biome theme whitelist mirrored by the ck_rpg_areas_theme /
     *  ck_rpg_rooms_theme CHECK constraints — validate here so bad
     *  input is a 400 field error, not a 500 from the DB. */
    public static final Set<String> THEMES =
            Set.of("plain", "desert", "forest", "dungeon", "cave", "town", "castle");

    private final RpgProjectRepository projects;
    private final RpgAreaRepository areas;
    private final RpgRoomRepository rooms;
    private final RpgExitRepository exits;
    private final RpgItemRepository items;
    private final RpgNpcRepository npcs;
    private final RpgScriptRepository scripts;
    private final RpgBuildingRepository buildings;
    private final RpgAssetRepository assets;

    public RpgProjectService(RpgProjectRepository projects, RpgAreaRepository areas, RpgRoomRepository rooms,
                             RpgExitRepository exits, RpgItemRepository items, RpgNpcRepository npcs,
                             RpgScriptRepository scripts, RpgBuildingRepository buildings,
                             RpgAssetRepository assets) {
        this.projects = projects;
        this.areas = areas;
        this.rooms = rooms;
        this.exits = exits;
        this.items = items;
        this.npcs = npcs;
        this.scripts = scripts;
        this.buildings = buildings;
        this.assets = assets;
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
        // Absent theme defaults to 'plain' (R2DBC writes every column,
        // so we can't lean on the DB default); a present theme must be
        // whitelisted. Thrown SYNCHRONOUSLY: the controller
        // @ExceptionHandler only sees exceptions raised during handler
        // invocation, not ones signalled inside the returned Mono.
        if (in.getTheme() == null || in.getTheme().isBlank()) {
            in.setTheme("plain");
        } else {
            requireKnownTheme(in.getTheme());
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
        // building_id/floor_level are written only by the building
        // service's auto-create — a user payload can't claim them.
        if (in.getBuildingId() != null || in.getFloorLevel() != null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "buildingId/floorLevel are managed by the buildings API"));
        }
        if (in.getTheme() != null && !in.getTheme().isBlank()) {
            requireKnownTheme(in.getTheme());
        }
        if (in.getTheme() != null && in.getTheme().isBlank()) in.setTheme(null);
        return get(projectId, ownerCasdoorId).flatMap(p -> {
            in.setProjectId(projectId);
            return rooms.save(in);
        });
    }

    public Mono<RpgRoom> updateRoom(UUID projectId, UUID roomId, RpgRoom in, String ownerCasdoorId) {
        if (in.getTheme() != null && !in.getTheme().isBlank()) {
            requireKnownTheme(in.getTheme());
        }
        return get(projectId, ownerCasdoorId).flatMap(p ->
                rooms.findById(roomId)
                        .filter(r -> projectId.equals(r.getProjectId()))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "room not found")))
                        .flatMap(existing -> {
                            // Slug is never updatable; be loud about it on
                            // building floors whose slugs the renderer's
                            // interior naming depends on.
                            if (in.getSlug() != null && !in.getSlug().equals(existing.getSlug())
                                    && existing.getBuildingId() != null) {
                                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "slug of a building floor room cannot change"));
                            }
                            // building linkage is service-managed only.
                            if ((in.getBuildingId() != null && !in.getBuildingId().equals(existing.getBuildingId()))
                                    || (in.getFloorLevel() != null && !in.getFloorLevel().equals(existing.getFloorLevel()))) {
                                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "buildingId/floorLevel are managed by the buildings API"));
                            }
                            existing.setName(in.getName());
                            existing.setDescription(in.getDescription());
                            existing.setEnvironment(in.getEnvironment());
                            existing.setX(in.getX()); existing.setY(in.getY()); existing.setZ(in.getZ());
                            existing.setIconAssetId(in.getIconAssetId());
                            existing.setGdScript(in.getGdScript());
                            existing.setTheme(in.getTheme() == null || in.getTheme().isBlank() ? null : in.getTheme());
                            return rooms.save(existing);
                        }));
    }

    public Mono<Void> deleteRoom(UUID projectId, UUID roomId, String ownerCasdoorId) {
        return get(projectId, ownerCasdoorId).flatMap(p ->
                rooms.findById(roomId)
                        // Cross-project rows are invisible, not deletable
                        // (fixes the old deleteById-anything IDOR).
                        .filter(r -> projectId.equals(r.getProjectId()))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "room not found")))
                        .flatMap(room -> {
                            if (room.getBuildingId() != null) {
                                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                        "room is a building floor — delete the building instead"));
                            }
                            return buildings.findByHostRoomId(roomId).next()
                                    .flatMap(b -> Mono.<Void>error(new ResponseStatusException(HttpStatus.CONFLICT,
                                            "room hosts building '" + b.getSlug() + "' — delete its buildings first")))
                                    // to_room_id has no DB cascade: clear
                                    // inbound exits first or the delete 500s.
                                    .switchIfEmpty(Mono.defer(() -> exits.deleteByToRoomId(roomId)
                                            .then(Mono.defer(() -> rooms.deleteById(roomId)))));
                        }));
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
        return get(projectId, ownerCasdoorId).flatMap(p ->
                exits.findById(exitId)
                        // Same IDOR fix as deleteRoom: only rows of THIS
                        // project are deletable through this path.
                        .filter(x -> projectId.equals(x.getProjectId()))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "exit not found")))
                        .flatMap(x -> exits.deleteById(exitId)));
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
        return get(projectId, ownerCasdoorId).flatMap(p -> {
            in.setProjectId(projectId);
            if (in.getPortraitAssetId() == null) {
                return npcs.save(in);
            }
            // PR #114 lesson: any asset referenced by another row MUST
            // belong to the SAME project — otherwise a publisher could
            // point their NPC at any other tenant's asset id and have
            // the renderer ship its bytes. The check needs a DB
            // round-trip, so it emits its 400 as a
            // ResponseStatusException from INSIDE the reactive chain
            // (the IllegalArgumentException @ExceptionHandler path only
            // sees synchronous throws and would surface this as a 500).
            return assets.findById(in.getPortraitAssetId())
                    .filter(a -> projectId.equals(a.getProjectId()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "portrait asset not found in this project")))
                    .flatMap(a -> npcs.save(in));
        });
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

    /** Theme whitelist gate. Throws (not Mono.error) so the controller
     *  {@code @ExceptionHandler(IllegalArgumentException.class)} turns
     *  it into a 400 field error. */
    private static void requireKnownTheme(String theme) {
        if (!THEMES.contains(theme)) {
            throw new IllegalArgumentException("unknown theme '" + theme + "' — expected one of "
                    + String.join(", ", THEMES.stream().sorted().toList()));
        }
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
