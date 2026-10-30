package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.CharacterEquipment;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OWASP IDOR fix: equip() must reject requests where the authenticated caller
 * does not own the target character.
 *
 * <ul>
 *   <li>USER-SITUATIONAL: owner can equip their own character.</li>
 *   <li>CROSS-USER DENY: caller with a different casdoor_id is FORBIDDEN.</li>
 *   <li>CROSS-USER DENY: null casdoor_id (seed / unowned characters) is FORBIDDEN.</li>
 * </ul>
 */
class ItemControllerEquipOwnerCheckTest {

    private PlayerCharacterRepository playerCharRepo;
    private CharacterEquipmentRepository equipRepo;
    private ItemController ctl;

    @BeforeEach
    void setUp() {
        playerCharRepo = mock(PlayerCharacterRepository.class);
        equipRepo      = mock(CharacterEquipmentRepository.class);
        ctl = new ItemController(
                mock(ItemRepository.class),
                mock(CharacterItemRepository.class),
                equipRepo,
                playerCharRepo,
                games.hugmesh.bff.security.GamesAdminPolicy.gamesRealm());
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("roles", List.of("player"))
                .build();
    }

    private static PlayerCharacter character(Long id, String casdoorId) {
        PlayerCharacter c = new PlayerCharacter();
        try {
            var idField  = PlayerCharacter.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        c.setCasdoorId(casdoorId);
        return c;
    }

    private static CharacterEquipment slot(Long characterId, String slot) {
        CharacterEquipment e = new CharacterEquipment();
        try {
            var cidField  = CharacterEquipment.class.getDeclaredField("characterId");
            var slotField = CharacterEquipment.class.getDeclaredField("slot");
            cidField.setAccessible(true);
            slotField.setAccessible(true);
            cidField.set(e, characterId);
            slotField.set(e, slot);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    @Test
    void equip_owner_succeeds() {
        PlayerCharacter c = character(1L, "player-uid-abc");
        CharacterEquipment eq = slot(1L, "weapon");
        when(playerCharRepo.findById(eq(1L))).thenReturn(Mono.just(c));
        when(equipRepo.findByCharacterIdAndSlot(1L, "weapon")).thenReturn(Mono.just(eq));
        when(equipRepo.save(any())).thenReturn(Mono.just(eq));

        StepVerifier.create(ctl.equip(1L, "weapon", 99L, jwt("player-uid-abc")))
                .verifyComplete();
    }

    @Test
    void equip_differentOwner_isForbidden() {
        // CROSS-USER DENY: a caller whose JWT subject does not match the character's
        // casdoor_id MUST receive 403.
        PlayerCharacter c = character(1L, "victim-uid");
        when(playerCharRepo.findById(eq(1L))).thenReturn(Mono.just(c));

        StepVerifier.create(ctl.equip(1L, "weapon", 99L, jwt("attacker-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void equip_nullCasdoorId_isForbidden() {
        // CROSS-USER DENY: seed / unowned characters (casdoor_id IS NULL in DB) MUST
        // never be equippable via the API — no caller can satisfy the owner check.
        PlayerCharacter c = character(35L, null);
        when(playerCharRepo.findById(eq(35L))).thenReturn(Mono.just(c));

        StepVerifier.create(ctl.equip(35L, "weapon", 99L, jwt("any-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void equip_characterNotFound_isNotFound() {
        when(playerCharRepo.findById(eq(999L))).thenReturn(Mono.empty());

        StepVerifier.create(ctl.equip(999L, "weapon", 99L, jwt("any-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }
}
