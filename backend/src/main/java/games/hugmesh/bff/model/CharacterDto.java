package games.hugmesh.bff.model;

/**
 * Public-facing projection of a {@link PlayerCharacter} entity, used on the
 * character SSE stream and the player dashboard. Only the fields the frontend
 * needs to render the HUD and character panel are included; internal persistence
 * details (casdoor owner id, timestamps, etc.) are intentionally omitted.
 *
 * <p>Instances are created exclusively via {@link #from(PlayerCharacter)} so that
 * the mapping logic lives in one place and the record remains immutable once built.
 *
 * @param id               Surrogate primary key of the {@code player_characters} row.
 * @param name             Display name chosen by the player for this character.
 * @param level            Current XP level of the character (not a chapter/stage —
 *                         see the naming note in {@code CLAUDE.md}).
 * @param xp               Total experience points accumulated at the current level.
 * @param xpToNext         Experience points required to reach the next level.
 * @param maxHp            Maximum hit points the character can have at full health.
 * @param currentHp        Current hit points; may be less than {@code maxHp} after
 *                         taking damage.
 * @param maxMp            Maximum magic points (mana) available to the character.
 * @param currentMp        Current magic points; consumed when casting instants.
 * @param attack           Physical attack stat, used in damage calculations.
 * @param defense          Physical defense stat, used to reduce incoming damage.
 * @param speed            Initiative/turn-order stat.
 * @param magicPower       Magic damage stat, used for instant (spell) calculations.
 * @param currentChapterId ID of the chapter the character is currently located in,
 *                         or {@code null} if the character has not yet entered a chapter.
 */
public record CharacterDto(
        Long id,
        String name,
        int level,
        int xp,
        int xpToNext,
        int maxHp,
        int currentHp,
        int maxMp,
        int currentMp,
        int attack,
        int defense,
        int speed,
        int magicPower,
        Long currentChapterId
) {
    /**
     * Constructs a {@code CharacterDto} by copying all relevant fields from the given
     * {@link PlayerCharacter} entity. This is the sole intended construction path.
     *
     * @param c the source character entity, must not be {@code null}
     * @return an immutable DTO populated from {@code c}
     */
    public static CharacterDto from(PlayerCharacter c) {
        return new CharacterDto(
                c.getId(), c.getName(),
                c.getLevel(), c.getXp(), c.getXpToNext(),
                c.getMaxHp(), c.getCurrentHp(),
                c.getMaxMp(), c.getCurrentMp(),
                c.getAttack(), c.getDefense(), c.getSpeed(), c.getMagicPower(),
                c.getCurrentChapterId()
        );
    }
}
