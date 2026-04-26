package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("items")
public class Item {

    @Id
    private Long id;
    private String name;
    private String itemType;
    private String description;
    private String effectJson;
    private String iconUrl;

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getItemType() { return itemType; }
    public String getDescription() { return description; }
    public String getEffectJson() { return effectJson; }
    public String getIconUrl() { return iconUrl; }
}
