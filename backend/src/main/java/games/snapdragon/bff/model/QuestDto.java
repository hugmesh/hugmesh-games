package games.snapdragon.bff.model;

public record QuestDto(
        Long id,
        String name,
        String description,
        int rewardXp,
        int rewardGold,
        String status
) {
    public static QuestDto from(Quest q, String status) {
        return new QuestDto(q.getId(), q.getName(), q.getDescription(),
                q.getRewardXp(), q.getRewardGold(), status);
    }

    public static QuestDto from(Quest q) {
        return from(q, null);
    }
}
