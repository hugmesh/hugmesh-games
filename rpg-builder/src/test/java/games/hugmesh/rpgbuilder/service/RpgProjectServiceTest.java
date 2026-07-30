package games.hugmesh.rpgbuilder.service;

import games.hugmesh.rpgbuilder.entity.*;
import games.hugmesh.rpgbuilder.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the validation paths the editor surfaces as inline
 * field errors. Each negative case asserts both the HTTP status the
 * controller will project (400 / 403 / 404) AND that the underlying
 * repository was NOT called — bad input must short-circuit before the
 * DB sees it.
 */
class RpgProjectServiceTest {

    private RpgProjectRepository projects;
    private RpgAreaRepository areas;
    private RpgRoomRepository rooms;
    private RpgExitRepository exits;
    private RpgItemRepository items;
    private RpgNpcRepository npcs;
    private RpgScriptRepository scripts;
    private RpgBuildingRepository buildings;
    private RpgAssetRepository assets;
    private RpgProjectService svc;

    @BeforeEach
    void setUp() {
        projects = mock(RpgProjectRepository.class);
        areas = mock(RpgAreaRepository.class);
        rooms = mock(RpgRoomRepository.class);
        exits = mock(RpgExitRepository.class);
        items = mock(RpgItemRepository.class);
        npcs = mock(RpgNpcRepository.class);
        scripts = mock(RpgScriptRepository.class);
        buildings = mock(RpgBuildingRepository.class);
        assets = mock(RpgAssetRepository.class);
        svc = new RpgProjectService(projects, areas, rooms, exits, items, npcs, scripts, buildings, assets);
    }

    /** Project owned by "alice" wired into the projects mock. */
    private UUID ownedProject() {
        UUID pid = UUID.randomUUID();
        RpgProject p = new RpgProject();
        p.setId(pid);
        p.setOwnerCasdoorId("alice");
        when(projects.findById(pid)).thenReturn(Mono.just(p));
        return pid;
    }

    @Test void create_rejectsInvalidSlug() {
        RpgProject p = new RpgProject();
        p.setSlug("Bad Slug");
        p.setDisplayName("Anything");
        StepVerifier.create(svc.create(p, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("slug"))
                .verify();
    }

    @Test void create_rejectsBlankDisplayName() {
        RpgProject p = new RpgProject();
        p.setSlug("ok-slug");
        p.setDisplayName("   ");
        StepVerifier.create(svc.create(p, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("displayName"))
                .verify();
    }

    @Test void create_persistsAndStampsOwner() {
        when(projects.save(any())).thenAnswer(inv -> {
            RpgProject saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        RpgProject p = new RpgProject();
        p.setSlug("good-slug");
        p.setDisplayName("Good Game");
        StepVerifier.create(svc.create(p, "alice"))
                .assertNext(saved -> {
                    org.junit.jupiter.api.Assertions.assertEquals("alice", saved.getOwnerCasdoorId());
                    org.junit.jupiter.api.Assertions.assertNotNull(saved.getId());
                })
                .verifyComplete();
    }

    @Test void get_rejectsForeignProject() {
        UUID id = UUID.randomUUID();
        RpgProject p = new RpgProject();
        p.setId(id);
        p.setOwnerCasdoorId("bob");
        when(projects.findById(id)).thenReturn(Mono.just(p));

        StepVerifier.create(svc.get(id, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 403)
                .verify();
    }

    @Test void createRoom_rejectsMissingArea() {
        UUID pid = UUID.randomUUID();
        RpgRoom r = new RpgRoom();
        r.setSlug("entrance");
        r.setName("Entrance");
        StepVerifier.create(svc.createRoom(pid, r, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("area"))
                .verify();
    }

    @Test void createExit_rejectsMissingDirection() {
        UUID pid = UUID.randomUUID();
        RpgExit x = new RpgExit();
        x.setFromRoomId(UUID.randomUUID());
        x.setToRoomId(UUID.randomUUID());
        StepVerifier.create(svc.createExit(pid, x, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("direction"))
                .verify();
    }

    // ── Biome themes ────────────────────────────────────────────────────────

    @Test void createArea_rejectsUnknownTheme() {
        UUID pid = ownedProject();
        RpgArea a = new RpgArea();
        a.setSlug("lava-land");
        a.setName("Lava Land");
        a.setTheme("lava");
        // Thrown synchronously so the controller @ExceptionHandler
        // (which never sees Mono.error signals) turns it into a 400.
        IllegalArgumentException err = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> svc.createArea(pid, a, "alice"));
        org.junit.jupiter.api.Assertions.assertTrue(err.getMessage().contains("lava"));
        verify(areas, never()).save(any());
    }

    @Test void createArea_defaultsThemeToPlain() {
        UUID pid = ownedProject();
        when(areas.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        RpgArea a = new RpgArea();
        a.setSlug("outlands");
        a.setName("Outlands");
        StepVerifier.create(svc.createArea(pid, a, "alice"))
                .assertNext(saved -> org.junit.jupiter.api.Assertions.assertEquals("plain", saved.getTheme()))
                .verifyComplete();
    }

    @Test void createArea_acceptsWhitelistedTheme() {
        UUID pid = ownedProject();
        when(areas.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        RpgArea a = new RpgArea();
        a.setSlug("bramblewick");
        a.setName("Bramblewick");
        a.setTheme("town");
        StepVerifier.create(svc.createArea(pid, a, "alice"))
                .assertNext(saved -> org.junit.jupiter.api.Assertions.assertEquals("town", saved.getTheme()))
                .verifyComplete();
    }

    @Test void createRoom_rejectsUnknownTheme() {
        UUID pid = UUID.randomUUID();
        RpgRoom r = new RpgRoom();
        r.setSlug("cove");
        r.setName("Cove");
        r.setAreaId(UUID.randomUUID());
        r.setTheme("swamp");
        IllegalArgumentException err = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> svc.createRoom(pid, r, "alice"));
        org.junit.jupiter.api.Assertions.assertTrue(err.getMessage().contains("swamp"));
        verify(rooms, never()).save(any());
    }

    @Test void createRoom_rejectsUserSuppliedBuildingLinkage() {
        UUID pid = UUID.randomUUID();
        RpgRoom r = new RpgRoom();
        r.setSlug("sneaky");
        r.setName("Sneaky");
        r.setAreaId(UUID.randomUUID());
        r.setBuildingId(UUID.randomUUID());
        r.setFloorLevel(0);
        StepVerifier.create(svc.createRoom(pid, r, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("buildings API"))
                .verify();
    }

    @Test void updateRoom_rejectsUnknownTheme() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.updateRoom(UUID.randomUUID(), UUID.randomUUID(), themedRoom("volcano"), "alice"));
    }

    private static RpgRoom themedRoom(String theme) {
        RpgRoom r = new RpgRoom();
        r.setName("Any");
        r.setTheme(theme);
        return r;
    }

    // ── Room / exit hardening (IDOR + building floors) ─────────────────────

    @Test void updateRoom_ignoresBuildingLinkageWhenUnchangedAndKeepsExisting() {
        UUID pid = ownedProject();
        UUID rid = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        RpgRoom existing = new RpgRoom();
        existing.setId(rid);
        existing.setProjectId(pid);
        existing.setSlug("town-inn-f0");
        existing.setBuildingId(buildingId);
        existing.setFloorLevel(0);
        when(rooms.findById(rid)).thenReturn(Mono.just(existing));
        when(rooms.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        RpgRoom in = new RpgRoom();
        in.setName("Renamed floor");
        // no slug, no building linkage in the payload
        StepVerifier.create(svc.updateRoom(pid, rid, in, "alice"))
                .assertNext(saved -> {
                    org.junit.jupiter.api.Assertions.assertEquals(buildingId, saved.getBuildingId());
                    org.junit.jupiter.api.Assertions.assertEquals(0, saved.getFloorLevel());
                    org.junit.jupiter.api.Assertions.assertEquals("town-inn-f0", saved.getSlug());
                    org.junit.jupiter.api.Assertions.assertEquals("Renamed floor", saved.getName());
                })
                .verifyComplete();
    }

    @Test void updateRoom_rejectsBuildingLinkageChange() {
        UUID pid = ownedProject();
        UUID rid = UUID.randomUUID();
        RpgRoom existing = new RpgRoom();
        existing.setId(rid);
        existing.setProjectId(pid);
        existing.setSlug("plaza");
        when(rooms.findById(rid)).thenReturn(Mono.just(existing));

        RpgRoom in = new RpgRoom();
        in.setName("Plaza");
        in.setBuildingId(UUID.randomUUID());
        in.setFloorLevel(2);
        StepVerifier.create(svc.updateRoom(pid, rid, in, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("buildings API"))
                .verify();
    }

    @Test void updateRoom_rejectsSlugChangeOnBuildingFloor() {
        UUID pid = ownedProject();
        UUID rid = UUID.randomUUID();
        RpgRoom existing = new RpgRoom();
        existing.setId(rid);
        existing.setProjectId(pid);
        existing.setSlug("town-inn-f0");
        existing.setBuildingId(UUID.randomUUID());
        existing.setFloorLevel(0);
        when(rooms.findById(rid)).thenReturn(Mono.just(existing));

        RpgRoom in = new RpgRoom();
        in.setName("Ground floor");
        in.setSlug("renamed");
        StepVerifier.create(svc.updateRoom(pid, rid, in, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("slug"))
                .verify();
    }

    @Test void updateRoom_rejectsCrossProjectRoom() {
        UUID pid = ownedProject();
        UUID rid = UUID.randomUUID();
        RpgRoom foreign = new RpgRoom();
        foreign.setId(rid);
        foreign.setProjectId(UUID.randomUUID()); // some other project
        when(rooms.findById(rid)).thenReturn(Mono.just(foreign));

        RpgRoom in = new RpgRoom();
        in.setName("Hijack");
        StepVerifier.create(svc.updateRoom(pid, rid, in, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
    }

    @Test void deleteRoom_rejectsCrossProjectRoom() {
        UUID pid = ownedProject();
        UUID rid = UUID.randomUUID();
        RpgRoom foreign = new RpgRoom();
        foreign.setId(rid);
        foreign.setProjectId(UUID.randomUUID());
        when(rooms.findById(rid)).thenReturn(Mono.just(foreign));

        StepVerifier.create(svc.deleteRoom(pid, rid, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
        verify(rooms, never()).deleteById(rid);
    }

    @Test void deleteRoom_rejectsBuildingFloorRoom() {
        UUID pid = ownedProject();
        UUID rid = UUID.randomUUID();
        RpgRoom floor = new RpgRoom();
        floor.setId(rid);
        floor.setProjectId(pid);
        floor.setBuildingId(UUID.randomUUID());
        floor.setFloorLevel(1);
        when(rooms.findById(rid)).thenReturn(Mono.just(floor));

        StepVerifier.create(svc.deleteRoom(pid, rid, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409)
                .verify();
        verify(rooms, never()).deleteById(rid);
    }

    @Test void deleteRoom_rejectsRoomHostingBuildings() {
        UUID pid = ownedProject();
        UUID rid = UUID.randomUUID();
        RpgRoom host = new RpgRoom();
        host.setId(rid);
        host.setProjectId(pid);
        when(rooms.findById(rid)).thenReturn(Mono.just(host));
        RpgBuilding b = new RpgBuilding();
        b.setSlug("inn");
        when(buildings.findByHostRoomId(rid)).thenReturn(Flux.just(b));

        StepVerifier.create(svc.deleteRoom(pid, rid, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409
                        && rse.getReason().contains("inn"))
                .verify();
        verify(rooms, never()).deleteById(rid);
    }

    @Test void deleteRoom_clearsInboundExitsBeforeDeleting() {
        UUID pid = ownedProject();
        UUID rid = UUID.randomUUID();
        RpgRoom room = new RpgRoom();
        room.setId(rid);
        room.setProjectId(pid);
        when(rooms.findById(rid)).thenReturn(Mono.just(room));
        when(buildings.findByHostRoomId(rid)).thenReturn(Flux.empty());
        when(exits.deleteByToRoomId(rid)).thenReturn(Mono.empty());
        when(rooms.deleteById(rid)).thenReturn(Mono.empty());

        StepVerifier.create(svc.deleteRoom(pid, rid, "alice")).verifyComplete();
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(exits, rooms);
        inOrder.verify(exits).deleteByToRoomId(rid);
        inOrder.verify(rooms).deleteById(rid);
    }

    @Test void deleteExit_rejectsCrossProjectExit() {
        UUID pid = ownedProject();
        UUID exid = UUID.randomUUID();
        RpgExit foreign = new RpgExit();
        foreign.setId(exid);
        foreign.setProjectId(UUID.randomUUID());
        when(exits.findById(exid)).thenReturn(Mono.just(foreign));

        StepVerifier.create(svc.deleteExit(pid, exid, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
        verify(exits, never()).deleteById(exid);
    }

    @Test void deleteExit_deletesOwnProjectExit() {
        UUID pid = ownedProject();
        UUID exid = UUID.randomUUID();
        RpgExit mine = new RpgExit();
        mine.setId(exid);
        mine.setProjectId(pid);
        when(exits.findById(exid)).thenReturn(Mono.just(mine));
        when(exits.deleteById(exid)).thenReturn(Mono.empty());

        StepVerifier.create(svc.deleteExit(pid, exid, "alice")).verifyComplete();
        verify(exits).deleteById(exid);
    }

    // ── NPC portrait IDOR (PR #114 lesson) ─────────────────────────────────

    private static RpgNpc npcWithPortrait(UUID portraitAssetId) {
        RpgNpc n = new RpgNpc();
        n.setSlug("town-greeter");
        n.setName("Guide Bramble");
        n.setDialogueText("Well met, traveller!");
        n.setPortraitAssetId(portraitAssetId);
        return n;
    }

    /** A portrait asset id belonging to ANOTHER project must be rejected
     *  400 — asserted as a ResponseStatusException emitted from INSIDE
     *  the reactive chain (the async DB check cannot ride the
     *  synchronous IllegalArgumentException @ExceptionHandler path). */
    @Test void createNpc_rejectsForeignProjectPortraitAsset() {
        UUID pid = ownedProject();
        RpgAsset foreign = new RpgAsset();
        foreign.setId(UUID.randomUUID());
        foreign.setProjectId(UUID.randomUUID());   // some other tenant's project
        when(assets.findById(foreign.getId())).thenReturn(Mono.just(foreign));

        StepVerifier.create(svc.createNpc(pid, npcWithPortrait(foreign.getId()), "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("portrait asset not found in this project"))
                .verify();
        verify(npcs, never()).save(any());
    }

    @Test void createNpc_rejectsMissingPortraitAsset() {
        UUID pid = ownedProject();
        UUID ghost = UUID.randomUUID();
        when(assets.findById(ghost)).thenReturn(Mono.empty());

        StepVerifier.create(svc.createNpc(pid, npcWithPortrait(ghost), "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("portrait asset not found in this project"))
                .verify();
        verify(npcs, never()).save(any());
    }

    @Test void createNpc_acceptsSameProjectPortraitAsset() {
        UUID pid = ownedProject();
        RpgAsset mine = new RpgAsset();
        mine.setId(UUID.randomUUID());
        mine.setProjectId(pid);
        when(assets.findById(mine.getId())).thenReturn(Mono.just(mine));
        when(npcs.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(svc.createNpc(pid, npcWithPortrait(mine.getId()), "alice"))
                .assertNext(saved -> {
                    org.junit.jupiter.api.Assertions.assertEquals(pid, saved.getProjectId());
                    org.junit.jupiter.api.Assertions.assertEquals(mine.getId(), saved.getPortraitAssetId());
                })
                .verifyComplete();
    }

    @Test void createNpc_acceptsNullPortrait() {
        UUID pid = ownedProject();
        when(npcs.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(svc.createNpc(pid, npcWithPortrait(null), "alice"))
                .assertNext(saved -> org.junit.jupiter.api.Assertions.assertNull(saved.getPortraitAssetId()))
                .verifyComplete();
        verify(assets, never()).findById(any(UUID.class));
    }

    @Test void listMine_returnsOwnedProjects() {
        // listMine takes the composite "sub|username" principal; both
        // halves get queried and merged. With distinct IDs on the
        // returned rows, the merged Flux yields 2.
        RpgProject p1 = new RpgProject(); p1.setId(UUID.randomUUID());
        RpgProject p2 = new RpgProject(); p2.setId(UUID.randomUUID());
        when(projects.findByOwnerCasdoorIdOrderByUpdatedAtDesc("alice"))
                .thenReturn(Flux.just(p1, p2));
        when(projects.findByOwnerCasdoorIdOrderByUpdatedAtDesc("alice-name"))
                .thenReturn(Flux.empty());
        StepVerifier.create(svc.listMine("alice|alice-name")).expectNextCount(2).verifyComplete();
    }
}
