package games.hugmesh.rpgbuilder.service;

import games.hugmesh.rpgbuilder.entity.*;
import games.hugmesh.rpgbuilder.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every validation path of the buildings API: town-theme gating, grid
 * bounds incl. footprint arithmetic, sibling overlap, interior-slug
 * collision (409, not a duplicate-key 500), immutable floors/basements,
 * shop-kind + same-project stock rules, and the transactional delete
 * order (stock → exits touching interiors from either end → interior
 * rooms → building) that `rpg_exits.to_room_id`'s missing cascade
 * makes mandatory. Repositories are interface mocks; the ownership
 * funnel is a REAL RpgProjectService (no mocking of concrete classes).
 */
class RpgBuildingServiceTest {

    private RpgProjectRepository projects;
    private RpgAreaRepository areas;
    private RpgRoomRepository rooms;
    private RpgExitRepository exits;
    private RpgItemRepository items;
    private RpgBuildingRepository buildings;
    private RpgShopStockRepository stocks;
    private RpgBuildingService svc;

    private UUID pid;
    private UUID areaId;
    private UUID hostRoomId;

    @BeforeEach
    void setUp() {
        projects = mock(RpgProjectRepository.class);
        areas = mock(RpgAreaRepository.class);
        rooms = mock(RpgRoomRepository.class);
        exits = mock(RpgExitRepository.class);
        items = mock(RpgItemRepository.class);
        buildings = mock(RpgBuildingRepository.class);
        stocks = mock(RpgShopStockRepository.class);
        RpgProjectService projectSvc = new RpgProjectService(projects, areas, rooms, exits, items,
                mock(RpgNpcRepository.class), mock(RpgScriptRepository.class), buildings,
                mock(RpgAssetRepository.class));
        svc = new RpgBuildingService(projectSvc, areas, rooms, exits, items, buildings, stocks);

        pid = UUID.randomUUID();
        RpgProject p = new RpgProject();
        p.setId(pid);
        p.setOwnerCasdoorId("alice");
        when(projects.findById(pid)).thenReturn(Mono.just(p));

        areaId = UUID.randomUUID();
        hostRoomId = UUID.randomUUID();
    }

    private void wireTownHost() {
        wireHost("town");
    }

    private void wireHost(String areaTheme) {
        RpgRoom host = new RpgRoom();
        host.setId(hostRoomId);
        host.setProjectId(pid);
        host.setAreaId(areaId);
        host.setSlug("market-square");
        when(rooms.findById(hostRoomId)).thenReturn(Mono.just(host));
        RpgArea area = new RpgArea();
        area.setId(areaId);
        area.setProjectId(pid);
        area.setSlug("bramblewick");
        area.setName("Bramblewick");
        area.setTheme(areaTheme);
        when(areas.findById(areaId)).thenReturn(Mono.just(area));
    }

    private RpgBuilding building(String slug, String kind, int gx, int gy, int w, int d,
                                 Integer floors, Integer basements) {
        RpgBuilding b = new RpgBuilding();
        b.setSlug(slug);
        b.setName(slug + " display name");
        b.setKind(kind);
        b.setHostRoomId(hostRoomId);
        b.setGridX(gx);
        b.setGridY(gy);
        b.setWidth(w);
        b.setDepth(d);
        b.setFloors(floors);
        b.setBasements(basements);
        return b;
    }

    /** Stubs for the happy save leg: no siblings, no slug clashes,
     *  echoing saves that mint ids. Returns collectors for the rooms
     *  and exits the auto-create persisted. */
    private List<Object>[] wireHappySaves() {
        when(buildings.findByHostRoomId(hostRoomId)).thenReturn(Flux.empty());
        when(rooms.findByProjectIdAndSlugIn(eq(pid), anyCollection())).thenReturn(Flux.empty());
        when(buildings.save(any())).thenAnswer(inv -> {
            RpgBuilding b = inv.getArgument(0);
            if (b.getId() == null) b.setId(UUID.randomUUID());
            return Mono.just(b);
        });
        List<Object> savedRooms = new ArrayList<>();
        when(rooms.save(any())).thenAnswer(inv -> {
            RpgRoom r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            savedRooms.add(r);
            return Mono.just(r);
        });
        List<Object> savedExits = new ArrayList<>();
        when(exits.save(any())).thenAnswer(inv -> {
            RpgExit x = inv.getArgument(0);
            x.setId(UUID.randomUUID());
            savedExits.add(x);
            return Mono.just(x);
        });
        @SuppressWarnings("unchecked")
        List<Object>[] collectors = new List[] { savedRooms, savedExits };
        return collectors;
    }

    // ── create: happy path ──────────────────────────────────────────────────

    @Test void create_autoCreatesInteriorRoomsAndStairExits() {
        wireTownHost();
        List<Object>[] saved = wireHappySaves();

        RpgBuilding in = building("inn", "inn", 2, 3, 2, 1, 2, 1);
        StepVerifier.create(svc.create(pid, in, "alice"))
                .assertNext(b -> {
                    assertEquals(pid, b.getProjectId());
                    assertEquals(areaId, b.getAreaId());
                })
                .verifyComplete();

        List<Object> savedRooms = saved[0];
        assertEquals(3, savedRooms.size());
        RpgRoom b1 = (RpgRoom) savedRooms.get(0);
        RpgRoom f0 = (RpgRoom) savedRooms.get(1);
        RpgRoom f1 = (RpgRoom) savedRooms.get(2);
        assertEquals("bramblewick-inn-b1", b1.getSlug());
        assertEquals("bramblewick-inn-f0", f0.getSlug());
        assertEquals("bramblewick-inn-f1", f1.getSlug());
        assertEquals(-1, b1.getFloorLevel());
        assertEquals(0, f0.getFloorLevel());
        assertEquals(1, f1.getFloorLevel());
        assertEquals("Basement 1", b1.getName());
        assertEquals("Ground floor", f0.getName());
        assertEquals("Floor 2", f1.getName());
        for (Object o : savedRooms) {
            RpgRoom r = (RpgRoom) o;
            assertEquals(pid, r.getProjectId());
            assertEquals(areaId, r.getAreaId());
            assertNull(r.getTheme()); // interiors inherit the area theme
            org.junit.jupiter.api.Assertions.assertNotNull(r.getBuildingId());
        }

        List<Object> savedExits = saved[1];
        assertEquals(4, savedExits.size()); // b1<->f0 + f0<->f1
        RpgExit upFromB1 = (RpgExit) savedExits.get(0);
        RpgExit downToB1 = (RpgExit) savedExits.get(1);
        RpgExit upFromF0 = (RpgExit) savedExits.get(2);
        RpgExit downToF0 = (RpgExit) savedExits.get(3);
        assertEquals(Direction.UP, upFromB1.getDirection());
        assertEquals(b1.getId(), upFromB1.getFromRoomId());
        assertEquals(f0.getId(), upFromB1.getToRoomId());
        assertEquals(Direction.DOWN, downToB1.getDirection());
        assertEquals(f0.getId(), downToB1.getFromRoomId());
        assertEquals(b1.getId(), downToB1.getToRoomId());
        assertEquals(Direction.UP, upFromF0.getDirection());
        assertEquals(f0.getId(), upFromF0.getFromRoomId());
        assertEquals(f1.getId(), upFromF0.getToRoomId());
        assertEquals(Direction.DOWN, downToF0.getDirection());
        assertEquals(f1.getId(), downToF0.getFromRoomId());
        assertEquals(f0.getId(), downToF0.getToRoomId());
    }

    @Test void create_singleFloorHouseGetsOneRoomNoStairs() {
        wireTownHost();
        List<Object>[] saved = wireHappySaves();
        StepVerifier.create(svc.create(pid, building("cottage", "house", 0, 0, 1, 1, 1, 0), "alice"))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, saved[0].size());
        assertEquals(0, saved[1].size());
        verify(exits, never()).save(any());
    }

    @Test void create_defaultsNullDimensionsToDbDefaults() {
        wireTownHost();
        List<Object>[] saved = wireHappySaves();
        RpgBuilding in = building("chapel", "church", 5, 5, 1, 1, null, null);
        in.setWidth(null);
        in.setDepth(null);
        StepVerifier.create(svc.create(pid, in, "alice"))
                .assertNext(b -> {
                    assertEquals(1, b.getWidth());
                    assertEquals(1, b.getDepth());
                    assertEquals(1, b.getFloors());
                    assertEquals(0, b.getBasements());
                })
                .verifyComplete();
        assertEquals(1, saved[0].size());
    }

    // ── create: validation ──────────────────────────────────────────────────

    @Test void create_rejectsNonTownArea() {
        wireHost("desert");
        StepVerifier.create(svc.create(pid, building("shed", "house", 0, 0, 1, 1, 1, 0), "alice"))
                .expectErrorMatches(is400containing("town"))
                .verify();
        verify(buildings, never()).save(any());
    }

    @Test void create_rejectsMissingHostRoom() {
        when(rooms.findById(hostRoomId)).thenReturn(Mono.empty());
        StepVerifier.create(svc.create(pid, building("shed", "house", 0, 0, 1, 1, 1, 0), "alice"))
                .expectErrorMatches(is400containing("host room"))
                .verify();
    }

    @Test void create_rejectsCrossProjectHostRoom() {
        RpgRoom foreign = new RpgRoom();
        foreign.setId(hostRoomId);
        foreign.setProjectId(UUID.randomUUID());
        foreign.setAreaId(areaId);
        when(rooms.findById(hostRoomId)).thenReturn(Mono.just(foreign));
        StepVerifier.create(svc.create(pid, building("shed", "house", 0, 0, 1, 1, 1, 0), "alice"))
                .expectErrorMatches(is400containing("host room"))
                .verify();
    }

    @Test void create_rejectsAreaMismatch() {
        wireTownHost();
        RpgBuilding in = building("shed", "house", 0, 0, 1, 1, 1, 0);
        in.setAreaId(UUID.randomUUID()); // not the host room's area
        StepVerifier.create(svc.create(pid, in, "alice"))
                .expectErrorMatches(is400containing("area"))
                .verify();
    }

    @Test void create_rejectsInteriorHostRoom() {
        RpgRoom interior = new RpgRoom();
        interior.setId(hostRoomId);
        interior.setProjectId(pid);
        interior.setAreaId(areaId);
        interior.setBuildingId(UUID.randomUUID());
        interior.setFloorLevel(0);
        when(rooms.findById(hostRoomId)).thenReturn(Mono.just(interior));
        StepVerifier.create(svc.create(pid, building("shed", "house", 0, 0, 1, 1, 1, 0), "alice"))
                .expectErrorMatches(is400containing("interior"))
                .verify();
    }

    @Test void create_rejectsNegativeOrigin() {
        StepVerifier.create(svc.create(pid, building("shed", "house", -1, 0, 1, 1, 1, 0), "alice"))
                .expectErrorMatches(is400containing("grid"))
                .verify();
    }

    @Test void create_rejectsFootprintPastGridEdge() {
        // origin in bounds, but 9+2 > 10 — the (15,15)-2x2 class of bug
        StepVerifier.create(svc.create(pid, building("barn", "house", 9, 0, 2, 1, 1, 0), "alice"))
                .expectErrorMatches(is400containing("grid"))
                .verify();
    }

    @Test void create_rejectsZeroWidth() {
        StepVerifier.create(svc.create(pid, building("shed", "house", 0, 0, 0, 1, 1, 0), "alice"))
                .expectErrorMatches(is400containing("width"))
                .verify();
    }

    @Test void create_rejectsZeroFloors() {
        StepVerifier.create(svc.create(pid, building("shed", "house", 0, 0, 1, 1, 0, 0), "alice"))
                .expectErrorMatches(is400containing("floors"))
                .verify();
    }

    @Test void create_rejectsNegativeBasements() {
        StepVerifier.create(svc.create(pid, building("shed", "house", 0, 0, 1, 1, 1, -1), "alice"))
                .expectErrorMatches(is400containing("basements"))
                .verify();
    }

    @Test void create_rejectsUnknownKind() {
        StepVerifier.create(svc.create(pid, building("bank", "bank", 0, 0, 1, 1, 1, 0), "alice"))
                .expectErrorMatches(is400containing("kind"))
                .verify();
    }

    @Test void create_rejectsInvalidSlug() {
        StepVerifier.create(svc.create(pid, building("Bad Slug", "house", 0, 0, 1, 1, 1, 0), "alice"))
                .expectErrorMatches(is400containing("slug"))
                .verify();
    }

    @Test void create_rejectsOverlappingFootprint() {
        wireTownHost();
        RpgBuilding sibling = building("smithy", "weapon_shop", 0, 0, 2, 2, 1, 0);
        sibling.setId(UUID.randomUUID());
        when(buildings.findByHostRoomId(hostRoomId)).thenReturn(Flux.just(sibling));
        StepVerifier.create(svc.create(pid, building("shed", "house", 1, 1, 1, 1, 1, 0), "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409
                        && rse.getReason().contains("smithy"))
                .verify();
        verify(buildings, never()).save(any());
    }

    @Test void create_allowsTouchingFootprints() {
        wireTownHost();
        List<Object>[] saved = wireHappySaves();
        RpgBuilding sibling = building("smithy", "weapon_shop", 0, 0, 2, 2, 1, 0);
        sibling.setId(UUID.randomUUID());
        when(buildings.findByHostRoomId(hostRoomId)).thenReturn(Flux.just(sibling));
        // (2,0) is the first free column — edge-adjacent, not overlapping
        StepVerifier.create(svc.create(pid, building("shed", "house", 2, 0, 1, 1, 1, 0), "alice"))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, saved[0].size());
    }

    @Test void create_rejectsInteriorSlugCollisionAs409() {
        wireTownHost();
        when(buildings.findByHostRoomId(hostRoomId)).thenReturn(Flux.empty());
        RpgRoom squatter = new RpgRoom();
        squatter.setSlug("bramblewick-inn-f0");
        when(rooms.findByProjectIdAndSlugIn(eq(pid), anyCollection())).thenReturn(Flux.just(squatter));
        StepVerifier.create(svc.create(pid, building("inn", "inn", 0, 0, 1, 1, 1, 0), "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409
                        && rse.getReason().contains("bramblewick-inn-f0"))
                .verify();
        verify(buildings, never()).save(any());
        verify(rooms, never()).save(any());
    }

    @Test void create_mapsBuildingSlugDuplicateTo409() {
        wireTownHost();
        when(buildings.findByHostRoomId(hostRoomId)).thenReturn(Flux.empty());
        when(rooms.findByProjectIdAndSlugIn(eq(pid), anyCollection())).thenReturn(Flux.empty());
        when(buildings.save(any())).thenReturn(Mono.error(new RuntimeException("duplicate key")));
        StepVerifier.create(svc.create(pid, building("inn", "inn", 0, 0, 1, 1, 1, 0), "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409
                        && rse.getReason().contains("inn"))
                .verify();
    }

    @Test void create_rejectsForeignPrincipal() {
        StepVerifier.create(svc.create(pid, building("shed", "house", 0, 0, 1, 1, 1, 0), "mallory"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 403)
                .verify();
    }

    // ── update ──────────────────────────────────────────────────────────────

    private RpgBuilding existingBuilding(String kind) {
        RpgBuilding b = building("inn", kind, 2, 2, 2, 2, 2, 1);
        b.setId(UUID.randomUUID());
        b.setProjectId(pid);
        b.setAreaId(areaId);
        when(buildings.findById(b.getId())).thenReturn(Mono.just(b));
        return b;
    }

    @Test void update_rejectsFloorsChange() {
        RpgBuilding existing = existingBuilding("inn");
        RpgBuilding in = new RpgBuilding();
        in.setFloors(3);
        StepVerifier.create(svc.update(pid, existing.getId(), in, "alice"))
                .expectErrorMatches(is400containing("floors is immutable"))
                .verify();
        verify(buildings, never()).save(any());
    }

    @Test void update_rejectsBasementsChange() {
        RpgBuilding existing = existingBuilding("inn");
        RpgBuilding in = new RpgBuilding();
        in.setBasements(0);
        StepVerifier.create(svc.update(pid, existing.getId(), in, "alice"))
                .expectErrorMatches(is400containing("basements is immutable"))
                .verify();
    }

    @Test void update_rejectsSlugChange() {
        RpgBuilding existing = existingBuilding("inn");
        RpgBuilding in = new RpgBuilding();
        in.setSlug("renamed");
        StepVerifier.create(svc.update(pid, existing.getId(), in, "alice"))
                .expectErrorMatches(is400containing("slug"))
                .verify();
    }

    @Test void update_rejectsHostRoomChange() {
        RpgBuilding existing = existingBuilding("inn");
        RpgBuilding in = new RpgBuilding();
        in.setHostRoomId(UUID.randomUUID());
        StepVerifier.create(svc.update(pid, existing.getId(), in, "alice"))
                .expectErrorMatches(is400containing("hostRoomId"))
                .verify();
    }

    @Test void update_movesOnGridAndRechecksOverlap() {
        RpgBuilding existing = existingBuilding("inn");
        RpgBuilding sibling = building("smithy", "weapon_shop", 5, 5, 2, 2, 1, 0);
        sibling.setId(UUID.randomUUID());
        when(buildings.findByHostRoomId(hostRoomId)).thenReturn(Flux.just(existing, sibling));
        RpgBuilding in = new RpgBuilding();
        in.setGridX(6);
        in.setGridY(6);
        StepVerifier.create(svc.update(pid, existing.getId(), in, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409
                        && rse.getReason().contains("smithy"))
                .verify();
    }

    @Test void update_allowsNameKindAndGridMove() {
        RpgBuilding existing = existingBuilding("tavern");
        when(buildings.findByHostRoomId(hostRoomId)).thenReturn(Flux.just(existing));
        when(buildings.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // tavern→inn is a change between two non-shop kinds: the service checks for
        // stranded stock; this building has none, so the change is allowed.
        when(stocks.findByBuildingId(existing.getId())).thenReturn(Flux.empty());
        RpgBuilding in = new RpgBuilding();
        in.setName("The Laughing Cactuar");
        in.setKind("inn");
        in.setGridX(7);
        StepVerifier.create(svc.update(pid, existing.getId(), in, "alice"))
                .assertNext(b -> {
                    assertEquals("The Laughing Cactuar", b.getName());
                    assertEquals("inn", b.getKind());
                    assertEquals(7, b.getGridX());
                    assertEquals(2, b.getGridY()); // untouched
                })
                .verifyComplete();
    }

    @Test void update_rejectsFootprintMovedPastEdge() {
        RpgBuilding existing = existingBuilding("inn"); // 2x2 footprint
        RpgBuilding in = new RpgBuilding();
        in.setGridX(9);
        StepVerifier.create(svc.update(pid, existing.getId(), in, "alice"))
                .expectErrorMatches(is400containing("grid"))
                .verify();
    }

    @Test void update_rejectsKindChangeToNonShopWhileStocked() {
        RpgBuilding existing = existingBuilding("item_shop");
        when(stocks.findByBuildingId(existing.getId())).thenReturn(Flux.just(new RpgShopStock()));
        RpgBuilding in = new RpgBuilding();
        in.setKind("house");
        StepVerifier.create(svc.update(pid, existing.getId(), in, "alice"))
                .expectErrorMatches(is400containing("stock"))
                .verify();
    }

    @Test void update_rejectsCrossProjectBuilding() {
        RpgBuilding foreign = building("inn", "inn", 0, 0, 1, 1, 1, 0);
        foreign.setId(UUID.randomUUID());
        foreign.setProjectId(UUID.randomUUID());
        when(buildings.findById(foreign.getId())).thenReturn(Mono.just(foreign));
        RpgBuilding in = new RpgBuilding();
        in.setName("Hijack");
        StepVerifier.create(svc.update(pid, foreign.getId(), in, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
    }

    // ── delete ──────────────────────────────────────────────────────────────

    @Test void delete_runsStockExitsRoomsBuildingInOrder() {
        RpgBuilding existing = existingBuilding("inn");
        UUID bid = existing.getId();
        RpgRoom f0 = new RpgRoom(); f0.setId(UUID.randomUUID());
        RpgRoom f1 = new RpgRoom(); f1.setId(UUID.randomUUID());
        when(stocks.deleteByBuildingId(bid)).thenReturn(Mono.empty());
        when(rooms.findByBuildingIdOrderByFloorLevelAsc(bid)).thenReturn(Flux.just(f0, f1));
        when(exits.deleteByFromRoomIdInOrToRoomIdIn(anyCollection(), anyCollection())).thenReturn(Mono.empty());
        when(rooms.deleteAllById(anyCollection())).thenReturn(Mono.empty());
        when(buildings.deleteById(bid)).thenReturn(Mono.empty());

        StepVerifier.create(svc.delete(pid, bid, "alice")).verifyComplete();

        InOrder inOrder = Mockito.inOrder(stocks, exits, rooms, buildings);
        inOrder.verify(stocks).deleteByBuildingId(bid);
        inOrder.verify(exits).deleteByFromRoomIdInOrToRoomIdIn(
                eq(List.of(f0.getId(), f1.getId())), eq(List.of(f0.getId(), f1.getId())));
        inOrder.verify(rooms).deleteAllById(eq(List.of(f0.getId(), f1.getId())));
        inOrder.verify(buildings).deleteById(bid);
    }

    @Test void delete_withNoInteriorRoomsSkipsExitSweep() {
        RpgBuilding existing = existingBuilding("house");
        UUID bid = existing.getId();
        when(stocks.deleteByBuildingId(bid)).thenReturn(Mono.empty());
        when(rooms.findByBuildingIdOrderByFloorLevelAsc(bid)).thenReturn(Flux.empty());
        when(buildings.deleteById(bid)).thenReturn(Mono.empty());

        StepVerifier.create(svc.delete(pid, bid, "alice")).verifyComplete();
        verify(exits, never()).deleteByFromRoomIdInOrToRoomIdIn(anyCollection(), anyCollection());
        verify(buildings).deleteById(bid);
    }

    @Test void delete_rejectsCrossProjectBuilding() {
        RpgBuilding foreign = building("inn", "inn", 0, 0, 1, 1, 1, 0);
        foreign.setId(UUID.randomUUID());
        foreign.setProjectId(UUID.randomUUID());
        when(buildings.findById(foreign.getId())).thenReturn(Mono.just(foreign));
        StepVerifier.create(svc.delete(pid, foreign.getId(), "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
        verify(buildings, never()).deleteById(any(UUID.class));
        verify(stocks, never()).deleteByBuildingId(any());
    }

    // ── stock ───────────────────────────────────────────────────────────────

    private RpgShopStock stockRow(UUID itemId, Integer buy, Integer sell) {
        RpgShopStock s = new RpgShopStock();
        s.setItemId(itemId);
        s.setBuyPrice(buy);
        s.setSellPrice(sell);
        return s;
    }

    private UUID ownedItem() {
        UUID itemId = UUID.randomUUID();
        RpgItem item = new RpgItem();
        item.setId(itemId);
        item.setProjectId(pid);
        item.setSlug("potion");
        when(items.findById(itemId)).thenReturn(Mono.just(item));
        return itemId;
    }

    @Test void createStock_happyDefaultsQtyToUnlimited() {
        RpgBuilding shop = existingBuilding("item_shop");
        UUID itemId = ownedItem();
        when(stocks.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        StepVerifier.create(svc.createStock(pid, shop.getId(), stockRow(itemId, 10, 5), "alice"))
                .assertNext(s -> {
                    assertEquals(pid, s.getProjectId());
                    assertEquals(shop.getId(), s.getBuildingId());
                    assertEquals(-1, s.getStockQty());
                })
                .verifyComplete();
    }

    @Test void createStock_allowsEveryPureShopKind() {
        // stub once — re-stubbing inside the loop would trip the
        // previous Answer with a null argument
        when(stocks.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // The 5 pure shops are exactly what the renderer bakes a shop counter for.
        for (String kind : List.of("item_shop", "weapon_shop", "armor_shop", "magic_shop", "accessory_shop")) {
            RpgBuilding shop = existingBuilding(kind);
            UUID itemId = ownedItem();
            StepVerifier.create(svc.createStock(pid, shop.getId(), stockRow(itemId, 10, 5), "alice"))
                    .expectNextCount(1)
                    .verifyComplete();
        }
    }

    @Test void createStock_rejectsInnAndTavern() {
        // inn/tavern render a rest zone, not a shop, so the renderer would silently
        // drop stock rows on them — reject at the API instead (contract must match
        // RpgGodotProjectRenderer.SHOP_KINDS). Kind is checked before the item
        // lookup, so no item stub is needed.
        for (String kind : List.of("inn", "tavern")) {
            RpgBuilding notAShop = existingBuilding(kind);
            StepVerifier.create(svc.createStock(pid, notAShop.getId(), stockRow(UUID.randomUUID(), 10, 5), "alice"))
                    .expectErrorMatches(is400containing("cannot stock"))
                    .verify();
        }
        verify(stocks, never()).save(any());
    }

    @Test void createStock_rejectsNonShopKinds() {
        for (String kind : List.of("church", "house")) {
            RpgBuilding notAShop = existingBuilding(kind);
            StepVerifier.create(svc.createStock(pid, notAShop.getId(), stockRow(UUID.randomUUID(), 10, 5), "alice"))
                    .expectErrorMatches(is400containing("cannot stock"))
                    .verify();
        }
        verify(stocks, never()).save(any());
    }

    @Test void createStock_rejectsForeignItem() {
        RpgBuilding shop = existingBuilding("item_shop");
        UUID itemId = UUID.randomUUID();
        RpgItem foreign = new RpgItem();
        foreign.setId(itemId);
        foreign.setProjectId(UUID.randomUUID()); // another publisher's item
        when(items.findById(itemId)).thenReturn(Mono.just(foreign));
        StepVerifier.create(svc.createStock(pid, shop.getId(), stockRow(itemId, 10, 5), "alice"))
                .expectErrorMatches(is400containing("item"))
                .verify();
        verify(stocks, never()).save(any());
    }

    @Test void createStock_rejectsNonPositiveBuyPrice() {
        StepVerifier.create(svc.createStock(pid, UUID.randomUUID(), stockRow(UUID.randomUUID(), 0, 0), "alice"))
                .expectErrorMatches(is400containing("buyPrice"))
                .verify();
    }

    @Test void createStock_rejectsNegativeSellPrice() {
        StepVerifier.create(svc.createStock(pid, UUID.randomUUID(), stockRow(UUID.randomUUID(), 10, -1), "alice"))
                .expectErrorMatches(is400containing("sellPrice"))
                .verify();
    }

    @Test void createStock_rejectsSellAboveBuy() {
        // buy 10 / sell 20 would be an infinite-gold loop
        StepVerifier.create(svc.createStock(pid, UUID.randomUUID(), stockRow(UUID.randomUUID(), 10, 20), "alice"))
                .expectErrorMatches(is400containing("sellPrice"))
                .verify();
    }

    @Test void createStock_rejectsQtyBelowSentinel() {
        RpgShopStock s = stockRow(UUID.randomUUID(), 10, 5);
        s.setStockQty(-2);
        StepVerifier.create(svc.createStock(pid, UUID.randomUUID(), s, "alice"))
                .expectErrorMatches(is400containing("stockQty"))
                .verify();
    }

    @Test void createStock_mapsDuplicateItemTo409() {
        RpgBuilding shop = existingBuilding("magic_shop");
        UUID itemId = ownedItem();
        when(stocks.save(any())).thenReturn(Mono.error(new RuntimeException("duplicate key")));
        StepVerifier.create(svc.createStock(pid, shop.getId(), stockRow(itemId, 10, 5), "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409)
                .verify();
    }

    @Test void deleteStock_rejectsRowOfAnotherBuilding() {
        UUID bid = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        RpgShopStock other = new RpgShopStock();
        other.setId(sid);
        other.setProjectId(pid);
        other.setBuildingId(UUID.randomUUID()); // parented elsewhere
        when(stocks.findById(sid)).thenReturn(Mono.just(other));
        StepVerifier.create(svc.deleteStock(pid, bid, sid, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
        verify(stocks, never()).deleteById(sid);
    }

    @Test void deleteStock_happy() {
        UUID bid = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        RpgShopStock mine = new RpgShopStock();
        mine.setId(sid);
        mine.setProjectId(pid);
        mine.setBuildingId(bid);
        when(stocks.findById(sid)).thenReturn(Mono.just(mine));
        when(stocks.deleteById(sid)).thenReturn(Mono.empty());
        StepVerifier.create(svc.deleteStock(pid, bid, sid, "alice")).verifyComplete();
        verify(stocks).deleteById(sid);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static java.util.function.Predicate<Throwable> is400containing(String fragment) {
        return err -> err instanceof ResponseStatusException rse
                && rse.getStatusCode().value() == 400
                && rse.getReason() != null
                && rse.getReason().contains(fragment);
    }
}
