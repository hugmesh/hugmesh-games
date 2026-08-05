package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

@Table("player_characters")
public class PlayerCharacter {

    @Id
    private Long id;
    private String name;
    private int level;
    private int xp;
    private int xpToNext;
    private int maxHp;
    private int currentHp;
    private int maxMp;
    private int currentMp;
    private int attack;
    private int defense;
    private int speed;
    private int magicPower;
    private Long currentChapterId;
    private String casdoorId;
    // user_avatars.id (Crypto.mintUid shape) — the per-character avatar
    // override; null falls back to the account's active avatar. The FK is
    // ON DELETE SET NULL so deleting an avatar never deletes the character.
    private String avatarId;
    private Instant createdAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getLevel() { return level; }
    public int getXp() { return xp; }
    public int getXpToNext() { return xpToNext; }
    public int getMaxHp() { return maxHp; }
    public int getCurrentHp() { return currentHp; }
    public int getMaxMp() { return maxMp; }
    public int getCurrentMp() { return currentMp; }
    public int getAttack() { return attack; }
    public int getDefense() { return defense; }
    public int getSpeed() { return speed; }
    public int getMagicPower() { return magicPower; }
    public Long getCurrentChapterId() { return currentChapterId; }
    public String getCasdoorId() { return casdoorId; }
    public String getAvatarId() { return avatarId; }
    public Instant getCreatedAt() { return createdAt; }

    public void setLevel(int level) { this.level = level; }
    public void setXp(int xp) { this.xp = xp; }
    public void setXpToNext(int xpToNext) { this.xpToNext = xpToNext; }
    public void setCurrentHp(int currentHp) { this.currentHp = currentHp; }
    public void setCurrentMp(int currentMp) { this.currentMp = currentMp; }
    public void setMaxHp(int maxHp) { this.maxHp = maxHp; }
    public void setMaxMp(int maxMp) { this.maxMp = maxMp; }
    public void setAttack(int attack) { this.attack = attack; }
    public void setDefense(int defense) { this.defense = defense; }
    public void setSpeed(int speed) { this.speed = speed; }
    public void setMagicPower(int magicPower) { this.magicPower = magicPower; }
    public void setCurrentChapterId(Long currentChapterId) { this.currentChapterId = currentChapterId; }
    public void setCasdoorId(String casdoorId) { this.casdoorId = casdoorId; }
    public void setAvatarId(String avatarId) { this.avatarId = avatarId; }
}
