package com.threewizards.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterDtoTest {

    @Test
    void fromCopiesEveryStatField() {
        PlayerCharacter c = new PlayerCharacter();
        c.setLevel(7);
        c.setXp(420);
        c.setXpToNext(1000);
        c.setMaxHp(100);
        c.setCurrentHp(73);
        c.setMaxMp(50);
        c.setCurrentMp(20);
        c.setAttack(15);
        c.setDefense(8);
        c.setSpeed(12);
        c.setMagicPower(22);
        c.setCurrentLevelId(3L);

        CharacterDto dto = CharacterDto.from(c);

        assertThat(dto.level()).isEqualTo(7);
        assertThat(dto.xp()).isEqualTo(420);
        assertThat(dto.xpToNext()).isEqualTo(1000);
        assertThat(dto.maxHp()).isEqualTo(100);
        assertThat(dto.currentHp()).isEqualTo(73);
        assertThat(dto.maxMp()).isEqualTo(50);
        assertThat(dto.currentMp()).isEqualTo(20);
        assertThat(dto.attack()).isEqualTo(15);
        assertThat(dto.defense()).isEqualTo(8);
        assertThat(dto.speed()).isEqualTo(12);
        assertThat(dto.magicPower()).isEqualTo(22);
        assertThat(dto.currentLevelId()).isEqualTo(3L);
    }
}
