package games.snapdragon.bff.model;

public record ItemDto(
        Long id,
        String name,
        String itemType,
        String description,
        String effectJson,
        String iconUrl,
        int quantity
) {
    public static ItemDto from(Item item, int quantity) {
        return new ItemDto(item.getId(), item.getName(), item.getItemType(),
                item.getDescription(), item.getEffectJson(), item.getIconUrl(), quantity);
    }

    public static ItemDto from(Item item) {
        return from(item, 1);
    }
}
