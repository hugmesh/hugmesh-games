package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.PlayerCharacter;
import games.hugmesh.bff.repository.CharacterEquipmentRepository;
import games.hugmesh.bff.repository.CharacterItemRepository;
import games.hugmesh.bff.repository.ItemRepository;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OWASP IDOR fix: the inventory/equipment read endpoints must reject requests
 * where the authenticated caller does not own the target character (mirrors
 * {@code CharacterController.ownedCharacter}).
 *
 * <ul>
 *   <li>USER-SITUATIONAL: owner can read their own character's inventory/equipment.</li>
 *   <li>CROSS-USER DENY: caller with a different casdoor_id is FORBIDDEN.</li>
 *   <li>ADMIN BYPASS: an {@code admin}-role caller may read any character.</li>
 *   <li>ENUMERATION: a missing character is NOT_FOUND before any ownership signal.</li>
 * </ul>
 */
class ItemControllerInventoryOwnerCheckTest {

    private PlayerCharacterRepository playerCharRepo;
    private CharacterItemRepository charItemRepo;
    private CharacterEquipmentRepository equipRepo;
    private ItemController ctl;

    @BeforeEach
    void setUp() {
        playerCharRepo = mock(PlayerCharacterRepository.class);
        charItemRepo   = mock(CharacterItemRepository.class);
        equipRepo      = mock(CharacterEquipmentRepository.class);
        ctl = new ItemController(
                mock(ItemRepository.class),
                charItemRepo,
                equipRepo,
                playerCharRepo,
                games.hugmesh.bff.security.GamesAdminPolicy.gamesRealm());
        // The controller assembles the downstream Flux eagerly (thenMany), so
        // the repos must return a publisher even in deny cases.
        when(charItemRepo.findByCharacterId(eq(1L))).thenReturn(Flux.empty());
        when(equipRepo.findByCharacterId(eq(1L))).thenReturn(Flux.empty());
        when(charItemRepo.findByCharacterId(eq(999L))).thenReturn(Flux.empty());
        when(equipRepo.findByCharacterId(eq(999L))).thenReturn(Flux.empty());
    }

    private static Jwt jwt(String subject, String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("roles", List.of(role))
                .build();
    }

    private static PlayerCharacter character(Long id, String casdoorId) {
        PlayerCharacter c = new PlayerCharacter();
        try {
            var idField = PlayerCharacter.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        c.setCasdoorId(casdoorId);
        return c;
    }

    private static void expectStatus(Flux<?> result, HttpStatus status) {
        StepVerifier.create(result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(status);
                })
                .verify();
    }

    // ── inventory ─────────────────────────────────────────────────────────

    @Test
    void inventory_owner_succeeds() {
        when(playerCharRepo.findById(eq(1L))).thenReturn(Mono.just(character(1L, "player-uid-abc")));

        StepVerifier.create(ctl.inventory(1L, jwt("player-uid-abc", "player")))
                .verifyComplete();
    }

    @Test
    void inventory_differentOwner_isForbidden() {
        // CROSS-USER DENY: a caller whose JWT subject does not match the
        // character's casdoor_id MUST receive 403.
        when(playerCharRepo.findById(eq(1L))).thenReturn(Mono.just(character(1L, "victim-uid")));

        expectStatus(ctl.inventory(1L, jwt("attacker-uid", "player")), HttpStatus.FORBIDDEN);
    }

    @Test
    void inventory_admin_succeeds() {
        // ADMIN BYPASS: moderation tooling may read any character's bag.
        when(playerCharRepo.findById(eq(1L))).thenReturn(Mono.just(character(1L, "victim-uid")));

        StepVerifier.create(ctl.inventory(1L, jwt("admin-uid", "admin")))
                .verifyComplete();
    }

    @Test
    void inventory_characterNotFound_isNotFound() {
        when(playerCharRepo.findById(eq(999L))).thenReturn(Mono.empty());

        expectStatus(ctl.inventory(999L, jwt("any-uid", "player")), HttpStatus.NOT_FOUND);
    }

    // ── equipment ─────────────────────────────────────────────────────────

    @Test
    void equipment_owner_succeeds() {
        when(playerCharRepo.findById(eq(1L))).thenReturn(Mono.just(character(1L, "player-uid-abc")));

        StepVerifier.create(ctl.equipment(1L, jwt("player-uid-abc", "player")))
                .verifyComplete();
    }

    @Test
    void equipment_differentOwner_isForbidden() {
        when(playerCharRepo.findById(eq(1L))).thenReturn(Mono.just(character(1L, "victim-uid")));

        expectStatus(ctl.equipment(1L, jwt("attacker-uid", "player")), HttpStatus.FORBIDDEN);
    }

    @Test
    void equipment_admin_succeeds() {
        when(playerCharRepo.findById(eq(1L))).thenReturn(Mono.just(character(1L, "victim-uid")));

        StepVerifier.create(ctl.equipment(1L, jwt("admin-uid", "admin")))
                .verifyComplete();
    }

    @Test
    void equipment_characterNotFound_isNotFound() {
        when(playerCharRepo.findById(eq(999L))).thenReturn(Mono.empty());

        expectStatus(ctl.equipment(999L, jwt("any-uid", "player")), HttpStatus.NOT_FOUND);
    }
}
