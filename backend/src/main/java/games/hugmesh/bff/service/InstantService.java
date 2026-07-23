package games.hugmesh.bff.service;

import games.hugmesh.bff.model.CharacterDto;
import games.hugmesh.bff.repository.CharacterSkillSlotRepository;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Resolves the slot → skill → mana_cost chain on a spell cast and applies the
 * MP drain to the character. Returns whether the cast succeeded so callers can
 * decide whether to also broadcast the input as a movement SSE event.
 *
 * <p>Reactive: the entire chain is non-blocking (R2DBC). On insufficient MP the
 * Mono completes with {@code false} and no DB writes happen.
 */
@Service
public class InstantService {

    private static final Logger log = LoggerFactory.getLogger(InstantService.class);

    private final CharacterSkillSlotRepository slotRepo;
    private final SkillRepository skillRepo;
    private final PlayerCharacterRepository characterRepo;
    private final GameStateService gameStateService;

    public InstantService(CharacterSkillSlotRepository slotRepo,
                        SkillRepository skillRepo,
                        PlayerCharacterRepository characterRepo,
                        GameStateService gameStateService) {
        this.slotRepo         = slotRepo;
        this.skillRepo        = skillRepo;
        this.characterRepo    = characterRepo;
        this.gameStateService = gameStateService;
    }

    /**
     * Drain MP for a spell cast. Returns {@code true} when the spell was
     * affordable + applied; {@code false} when MP is insufficient (caller
     * should NOT broadcast the cast to the SSE stream in that case).
     */
    public Mono<Boolean> castInstant(Long characterId, int slot) {
        return slotRepo.findByCharacterIdAndSlotNumber(characterId, slot)
                .flatMap(s -> s.getSkillId() == null ? Mono.empty() : skillRepo.findById(s.getSkillId()))
                .flatMap(skill -> characterRepo.findById(characterId).flatMap(c -> {
                    int cost = skill.getManaCost();
                    if (c.getCurrentMp() < cost) {
                        log.info("[spell] character {} cannot cast '{}' — MP {} < cost {}",
                                characterId, skill.getName(), c.getCurrentMp(), cost);
                        return Mono.just(false);
                    }
                    c.setCurrentMp(c.getCurrentMp() - cost);
                    return characterRepo.save(c)
                            .doOnNext(saved -> {
                                gameStateService.notifyCharacterStatChange(CharacterDto.from(saved));
                                log.info("[spell] {} cast '{}' (-{} MP, now {}/{} MP)",
                                        characterId, skill.getName(), cost,
                                        saved.getCurrentMp(), saved.getMaxMp());
                            })
                            .thenReturn(true);
                }))
                // Empty slot, no skill set, or character not found → silently succeed
                // so the visual still fires. The HUD will not show MP changes.
                .defaultIfEmpty(true);
    }
}
