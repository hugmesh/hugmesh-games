package com.threewizards.repository;

import com.threewizards.model.Skill;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface SkillRepository extends ReactiveCrudRepository<Skill, Long> {}
