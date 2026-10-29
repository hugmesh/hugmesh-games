package games.hugmesh.bff.controller;

import games.hugmesh.bff.security.GamesAdminPolicy;

import games.hugmesh.bff.model.ItemDto;
import games.hugmesh.bff.model.PlayerCharacter;
import games.hugmesh.bff.repository.CharacterEquipmentRepository;
import games.hugmesh.bff.repository.CharacterItemRepository;
import games.hugmesh.bff.repository.ItemRepository;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

@Tag(name = "Items", description = "Inventory and equipment management. Requires authentication.")
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemRepository itemRepo;
    private final CharacterItemRepository charItemRepo;
    private final CharacterEquipmentRepository equipRepo;
    private final PlayerCharacterRepository playerCharRepo;
    /** Who is a games admin (realm-aware; see GamesAdminPolicy). */
    private final GamesAdminPolicy gamesAdmin;

    public ItemController(ItemRepository itemRepo,
                          CharacterItemRepository charItemRepo,
                          CharacterEquipmentRepository equipRepo,
                          PlayerCharacterRepository playerCharRepo,
                          GamesAdminPolicy gamesAdmin) {
        this.gamesAdmin = gamesAdmin;
        this.itemRepo        = itemRepo;
        this.charItemRepo    = charItemRepo;
        this.equipRepo       = equipRepo;
        this.playerCharRepo  = playerCharRepo;
    }

    /**
     * Object-level authorization gate (ASVS 4.1.3 / OWASP A01), mirroring
     * {@code CharacterController.ownedCharacter}: loads the character by id and
     * proves the caller may touch it before any read runs. The JWT subject must
     * equal the character's owning {@code casdoorId}, OR the caller holds the
     * {@code admin} role. Fails closed with 403 (404 first if the character
     * doesn't exist, so ownership can't be probed as an enumeration oracle).
     */
    private Mono<PlayerCharacter> ownedCharacter(Long id, Jwt jwt) {
        return playerCharRepo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(c -> {
                    String caller = jwt == null ? null : jwt.getSubject();
                    boolean owns = caller != null && Objects.equals(caller, c.getCasdoorId());
                    if (!owns && !gamesAdmin.isGamesAdmin(jwt)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "character does not belong to the caller"));
                    }
                    return Mono.just(c);
                });
    }

    @Operation(
        summary = "Character inventory",
        description = """
            Returns all items in the character's bag with quantities.

            ```
            ──request──► charItemRepo.findByCharacterId(id)
                          ──flatMap(ci ->
                              itemRepo.findById(ci.itemId)
                               .map(item -> ItemDto{…, quantity: ci.quantity}))
             ──────────────●──●──●──|
                           ItemDto (with quantity)…
            ```
            """)
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/character/{characterId}/inventory")
    public Flux<ItemDto> inventory(@PathVariable Long characterId,
                                   @AuthenticationPrincipal Jwt jwt) {
        return ownedCharacter(characterId, jwt)
                .thenMany(charItemRepo.findByCharacterId(characterId)
                        .flatMap(ci -> itemRepo.findById(ci.getItemId())
                                .map(item -> ItemDto.from(item, ci.getQuantity()))));
    }

    @Operation(
        summary = "Character equipment",
        description = """
            Returns all equipment slots. Empty slots have `item: null` / omit the key.

            ```
            ──request──► equipRepo.findByCharacterId(id)
                          ──flatMap(ce ->
                              ce.itemId == null
                                ? Mono.just({slot})
                                : itemRepo.findById(ce.itemId)
                                    .map({slot, item: ItemDto}))
             ──────────────●──●──●──●──|
                {slot:"head",  item:{…}}
                {slot:"chest", item:null}  ← empty
                …
            ```
            """)
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/character/{characterId}/equipment")
    public Flux<Map<String, Object>> equipment(@PathVariable Long characterId,
                                               @AuthenticationPrincipal Jwt jwt) {
        return ownedCharacter(characterId, jwt)
                .thenMany(equipRepo.findByCharacterId(characterId)
                        .flatMap(ce -> ce.getItemId() == null
                                ? Mono.just(Map.of("slot", ce.getSlot()))
                                : itemRepo.findById(ce.getItemId())
                                        .map(item -> Map.of("slot", (Object) ce.getSlot(), "item", ItemDto.from(item)))));
    }

    @Operation(
        summary = "Equip an item to a slot",
        description = """
            Sets `itemId` on an existing equipment slot. Slot must already exist.
            Pass `itemId` as a query param.

            ```
            ──request──► equipRepo.findByCharacterIdAndSlot(charId, slot)
                               │
                     ┌─────────┴──────────┐
                     │ found              │ empty
                     ▼                    ▼
              setItemId(itemId)     switchIfEmpty(404)
              equipRepo.save()            ──✗
                     │
              ──────●─|  (empty body)
            ```
            """)
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/character/{characterId}/equipment/{slot}")
    public Mono<Void> equip(
            @PathVariable Long characterId,
            @PathVariable String slot,
            @RequestParam Long itemId,
            @AuthenticationPrincipal Jwt jwt) {
        String subject = jwt.getSubject();
        return playerCharRepo.findById(characterId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(c -> {
                    if (c.getCasdoorId() == null || !c.getCasdoorId().equals(subject)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                    }
                    return Mono.just(c);
                })
                .flatMap(c -> equipRepo.findByCharacterIdAndSlot(characterId, slot)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                        .doOnNext(e -> e.setItemId(itemId))
                        .flatMap(equipRepo::save))
                .then();
    }
}
