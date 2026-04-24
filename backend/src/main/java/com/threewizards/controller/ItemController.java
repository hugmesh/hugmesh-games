package com.threewizards.controller;

import com.threewizards.model.ItemDto;
import com.threewizards.repository.CharacterEquipmentRepository;
import com.threewizards.repository.CharacterItemRepository;
import com.threewizards.repository.ItemRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Tag(name = "Items", description = "Inventory and equipment management. Requires authentication.")
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemRepository itemRepo;
    private final CharacterItemRepository charItemRepo;
    private final CharacterEquipmentRepository equipRepo;

    public ItemController(ItemRepository itemRepo,
                          CharacterItemRepository charItemRepo,
                          CharacterEquipmentRepository equipRepo) {
        this.itemRepo      = itemRepo;
        this.charItemRepo  = charItemRepo;
        this.equipRepo     = equipRepo;
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
    @GetMapping("/character/{characterId}/inventory")
    public Flux<ItemDto> inventory(@PathVariable Long characterId) {
        return charItemRepo.findByCharacterId(characterId)
                .flatMap(ci -> itemRepo.findById(ci.getItemId())
                        .map(item -> ItemDto.from(item, ci.getQuantity())));
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
    @GetMapping("/character/{characterId}/equipment")
    public Flux<Map<String, Object>> equipment(@PathVariable Long characterId) {
        return equipRepo.findByCharacterId(characterId)
                .flatMap(ce -> ce.getItemId() == null
                        ? Mono.just(Map.of("slot", ce.getSlot()))
                        : itemRepo.findById(ce.getItemId())
                                .map(item -> Map.of("slot", (Object) ce.getSlot(), "item", ItemDto.from(item))));
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
    @PutMapping("/character/{characterId}/equipment/{slot}")
    public Mono<Void> equip(
            @PathVariable Long characterId,
            @PathVariable String slot,
            @RequestParam Long itemId) {
        return equipRepo.findByCharacterIdAndSlot(characterId, slot)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .doOnNext(e -> e.setItemId(itemId))
                .flatMap(equipRepo::save)
                .then();
    }
}
