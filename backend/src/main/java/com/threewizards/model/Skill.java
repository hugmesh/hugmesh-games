package com.threewizards.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("skills")
public class Skill {

    @Id
    private Long id;
    private String name;
    private String skillType;
    private String description;
    private int manaCost;
    private String effectJson;
    private String iconUrl;

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSkillType() { return skillType; }
    public String getDescription() { return description; }
    public int getManaCost() { return manaCost; }
    public String getEffectJson() { return effectJson; }
    public String getIconUrl() { return iconUrl; }
}
