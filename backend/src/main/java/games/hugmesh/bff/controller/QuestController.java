package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.QuestDto;
import games.hugmesh.bff.repository.CharacterQuestRepository;
import games.hugmesh.bff.repository.QuestRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@Tag(name = "Quests", description = "Quest catalogue and per-character progress. Requires authentication.")
@RestController
@RequestMapping("/api/quests")
public class QuestController {

    private final QuestRepository questRepo;
    private final CharacterQuestRepository charQuestRepo;

    public QuestController(QuestRepository questRepo, CharacterQuestRepository charQuestRepo) {
        this.questRepo      = questRepo;
        this.charQuestRepo  = charQuestRepo;
    }

    @Operation(
        summary = "List all quests",
        description = """
            Returns the full quest catalogue regardless of character progress.

            ```
            ──request──► questRepo.findAll()
                          ──map(QuestDto::from)──►
             ──────────────●──●──●──|
                           QuestDto…
            ```
            """)
    @GetMapping
    public Flux<QuestDto> allQuests() {
        return questRepo.findAll().map(QuestDto::from);
    }

    @Operation(
        summary = "Quests for a character",
        description = """
            Returns only quests the character has started, enriched with their
            completion status and `completedAt` timestamp.

            ```
            ──request──► charQuestRepo.findByCharacterId(id)
                          ──flatMap(cq ->
                              questRepo.findById(cq.questId)
                               .map(q -> QuestDto{…,
                                          status: cq.status,
                                          completedAt: cq.completedAt}))
             ──────────────●──●──|
                           QuestDto (with character status)…
            ```
            """)
    @GetMapping("/character/{characterId}")
    public Flux<QuestDto> characterQuests(@PathVariable Long characterId) {
        return charQuestRepo.findByCharacterId(characterId)
                .flatMap(cq -> questRepo.findById(cq.getQuestId())
                        .map(q -> QuestDto.from(q, cq.getStatus())));
    }
}
