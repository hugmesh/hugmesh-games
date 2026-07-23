package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("quests")
public class Quest {

    @Id
    private Long id;
    private String name;
    private String description;
    private int rewardXp;
    private int rewardGold;
    private Long rewardSkillId;
    private Long chapterId;

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getRewardXp() { return rewardXp; }
    public int getRewardGold() { return rewardGold; }
    public Long getRewardSkillId() { return rewardSkillId; }
    public Long getChapterId() { return chapterId; }
}
