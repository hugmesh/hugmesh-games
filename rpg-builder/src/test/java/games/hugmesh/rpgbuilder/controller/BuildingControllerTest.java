package games.hugmesh.rpgbuilder.controller;

import games.hugmesh.rpgbuilder.entity.RpgBuilding;
import games.hugmesh.rpgbuilder.entity.RpgShopStock;
import games.hugmesh.rpgbuilder.service.RpgBuildingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Wire contract for the buildings API: service-layer statuses (400 /
 * 403-role / 404 / 409) reach the HTTP client unchanged, happy paths
 * serialize the entity-as-DTO, and the role gate matches
 * RpgProjectController (ADMIN|PUBLISHER only). The service is a
 * test-double SUBCLASS, not a Mockito mock of the concrete class
 * (Java 25 / ByteBuddy constraint this repo pins elsewhere).
 */
@WebFluxTest(controllers = BuildingController.class)
@Import(BuildingControllerTest.MockConfig.class)
@EnableReactiveMethodSecurity
class BuildingControllerTest {

    @Autowired WebTestClient web;
    @Autowired RpgBuildingService svc;

    private StubBuildingService stub() { return (StubBuildingService) svc; }

    @org.junit.jupiter.api.BeforeEach
    void resetStub() { stub().reset(); } // the bean outlives each test

    @Test
    void post_happy_returnsCreatedJson() {
        RpgBuilding saved = new RpgBuilding();
        saved.setId(UUID.randomUUID());
        saved.setSlug("tonberry-goods");
        saved.setName("Tonberry Goods");
        saved.setKind("item_shop");
        stub().createResult = Mono.just(saved);

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"tonberry-goods\",\"name\":\"Tonberry Goods\",\"kind\":\"item_shop\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.slug").isEqualTo("tonberry-goods")
                .jsonPath("$.kind").isEqualTo("item_shop");
    }

    @Test
    void post_nonTownArea_returns400() {
        stub().createResult = Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "buildings can only be placed in town-themed areas"));
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"shed\",\"name\":\"Shed\",\"kind\":\"house\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void post_overlap_returns409() {
        stub().createResult = Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                "building footprint overlaps 'smithy'"));
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"shed\",\"name\":\"Shed\",\"kind\":\"house\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void post_illegalArgumentThrownSynchronously_returns400() {
        // Pins the @ExceptionHandler contract the theme validation
        // relies on: the service throws IllegalArgumentException
        // during handler invocation (NOT via Mono.error — async
        // signals bypass @ExceptionHandler and become 500s).
        stub().createThrows = new IllegalArgumentException("unknown theme 'lava'");
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"shed\",\"name\":\"Shed\",\"kind\":\"house\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void put_immutableFloors_returns400() {
        stub().updateResult = Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "floors is immutable after create — delete and re-create the building"));
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .put().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings/" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"floors\":3}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void delete_happy_returns204() {
        stub().deleteResult = Mono.empty();
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .delete().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings/" + UUID.randomUUID())
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void get_list_returnsJsonArray() {
        RpgBuilding b = new RpgBuilding();
        b.setId(UUID.randomUUID());
        b.setSlug("gilgamesh-arms");
        stub().listResult = Flux.just(b);
        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].slug").isEqualTo("gilgamesh-arms");
    }

    @Test
    void stock_post_happy_returnsCreated() {
        RpgShopStock s = new RpgShopStock();
        s.setId(UUID.randomUUID());
        s.setBuyPrice(10);
        s.setSellPrice(5);
        s.setStockQty(-1);
        stub().createStockResult = Mono.just(s);
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings/" + UUID.randomUUID() + "/stock")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"itemId\":\"" + UUID.randomUUID() + "\",\"buyPrice\":10,\"sellPrice\":5}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.stockQty").isEqualTo(-1);
    }

    @Test
    void stock_post_foreignItem_returns400() {
        stub().createStockResult = Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "item not found in this project"));
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings/" + UUID.randomUUID() + "/stock")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"itemId\":\"" + UUID.randomUUID() + "\",\"buyPrice\":10,\"sellPrice\":5}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void stock_delete_happy_returns204() {
        stub().deleteStockResult = Mono.empty();
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .delete().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings/" + UUID.randomUUID()
                        + "/stock/" + UUID.randomUUID())
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void unauthenticated_isRejected() {
        web.get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void plainUserRole_isForbidden() {
        web.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/buildings")
                .exchange()
                .expectStatus().isForbidden();
    }

    private static JwtMutator asPublisher() {
        return mockJwt()
                .authorities(new SimpleGrantedAuthority("ROLE_PUBLISHER"))
                .jwt(j -> j.subject("alice"));
    }

    /** Test-double subclass: each endpoint returns whatever the test
     *  parked in the matching field. */
    static class StubBuildingService extends RpgBuildingService {
        Flux<RpgBuilding> listResult = Flux.empty();
        Mono<RpgBuilding> createResult = Mono.empty();
        RuntimeException createThrows;
        Mono<RpgBuilding> updateResult = Mono.empty();
        Mono<Void> deleteResult = Mono.empty();
        Flux<RpgShopStock> listStockResult = Flux.empty();
        Mono<RpgShopStock> createStockResult = Mono.empty();
        Mono<Void> deleteStockResult = Mono.empty();

        StubBuildingService() { super(null, null, null, null, null, null, null); }

        void reset() {
            listResult = Flux.empty();
            createResult = Mono.empty();
            createThrows = null;
            updateResult = Mono.empty();
            deleteResult = Mono.empty();
            listStockResult = Flux.empty();
            createStockResult = Mono.empty();
            deleteStockResult = Mono.empty();
        }

        @Override public Flux<RpgBuilding> list(UUID projectId, String principal) { return listResult; }
        @Override public Mono<RpgBuilding> create(UUID projectId, RpgBuilding in, String principal) {
            if (createThrows != null) throw createThrows;
            return createResult;
        }
        @Override public Mono<RpgBuilding> update(UUID projectId, UUID buildingId, RpgBuilding in, String principal) { return updateResult; }
        @Override public Mono<Void> delete(UUID projectId, UUID buildingId, String principal) { return deleteResult; }
        @Override public Flux<RpgShopStock> listStock(UUID projectId, UUID buildingId, String principal) { return listStockResult; }
        @Override public Mono<RpgShopStock> createStock(UUID projectId, UUID buildingId, RpgShopStock in, String principal) { return createStockResult; }
        @Override public Mono<Void> deleteStock(UUID projectId, UUID buildingId, UUID stockId, String principal) { return deleteStockResult; }
    }

    @TestConfiguration
    static class MockConfig {
        @Bean RpgBuildingService rpgBuildingService() { return new StubBuildingService(); }
    }
}
