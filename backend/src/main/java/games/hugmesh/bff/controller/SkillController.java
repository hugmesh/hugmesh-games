package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.SkillDto;
import games.hugmesh.bff.repository.CharacterSkillRepository;
import games.hugmesh.bff.repository.CharacterSkillSlotRepository;
import games.hugmesh.bff.repository.SkillRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag(name = "Skills", description = "Skill catalogue and per-character learned skills / hotbar slots. Requires authentication.")
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRepository skillRepo;
    private final CharacterSkillRepository charSkillRepo;
    private final CharacterSkillSlotRepository slotRepo;

    public SkillController(SkillRepository skillRepo,
                           CharacterSkillRepository charSkillRepo,
                           CharacterSkillSlotRepository slotRepo) {
        this.skillRepo     = skillRepo;
        this.charSkillRepo = charSkillRepo;
        this.slotRepo      = slotRepo;
    }

    @Operation(
        summary = "List all skills",
        description = """
            Returns the complete skill catalogue regardless of what any character has learned.

            ```
            ──request──► skillRepo.findAll()
                          ──map(SkillDto::from)──►
             ──────────────●──●──●──●──|
                           SkillDto…
            ```
            """)
    @GetMapping
    public Flux<SkillDto> allSkills() {
        return skillRepo.findAll().map(SkillDto::from);
    }

    @Operation(
        summary = "Skills learned by a character",
        description = """
            Returns only skills the character has actually learned (rows in `character_skills`).

            ```
            ──request──► charSkillRepo.findByCharacterId(id)
                          ──flatMap(cs -> skillRepo.findById(cs.skillId))
                          ──map(SkillDto::from)──►
             ──────────────●──●──●──|  (only learned skills)
                           SkillDto…
            ```
            """)
    @GetMapping("/character/{characterId}")
    public Flux<SkillDto> characterSkills(@PathVariable Long characterId) {
        return charSkillRepo.findByCharacterId(characterId)
                .flatMap(cs -> skillRepo.findById(cs.getSkillId()))
                .map(SkillDto::from);
    }

    @Operation(
        summary = "Hotbar skill slots",
        description = """
            Returns all skill slots for the character ordered by slot number.
            Empty slots are included with `skill: null`.

            ```
            ──request──► slotRepo.findByCharacterIdOrderBySlotNumber(id)
                          ──flatMap(slot ->
                              slot.skillId == null
                                ? Mono.just({slot, skill: null})
                                : skillRepo.findById(slot.skillId)
                                    .map({slot, skill: SkillDto}))
             ──────────────●──●──●──●──●──|
                {slot:1, skill:{…}}
                {slot:2, skill:null}   ← empty
                …
            ```
            """)
    @GetMapping("/character/{characterId}/slots")
    public Flux<Object> skillSlots(@PathVariable Long characterId) {
        return slotRepo.findByCharacterIdOrderBySlotNumber(characterId)
                .flatMap(slot -> slot.getSkillId() == null
                        ? Mono.just(java.util.Map.of("slot", slot.getSlotNumber(), "skill", (Object) null))
                        : skillRepo.findById(slot.getSkillId())
                                .map(sk -> java.util.Map.of("slot", slot.getSlotNumber(), "skill", SkillDto.from(sk))));
    }

    @Operation(
        summary = "Assign skill to a hotbar slot",
        description = """
            Sets `skillId` on the given slot number. Slot must already exist (created
            during character setup). Pass `skillId` as a query param.

            ```
            ──request──► slotRepo.findByCharacterIdAndSlotNumber(charId, slot)
                               │
                     ┌─────────┴──────────┐
                     │ found              │ empty
                     ▼                    ▼
              setSkillId(skillId)   switchIfEmpty(404)
              slotRepo.save()             ──✗
                     │
              ──────●─|  (empty body)
            ```
            """)
    @PutMapping("/character/{characterId}/slots/{slot}")
    public Mono<Void> assignToSlot(
            @PathVariable Long characterId,
            @PathVariable int slot,
            @RequestParam Long skillId) {
        return slotRepo.findByCharacterIdAndSlotNumber(characterId, slot)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .doOnNext(s -> s.setSkillId(skillId))
                .flatMap(slotRepo::save)
                .then();
    }
}
