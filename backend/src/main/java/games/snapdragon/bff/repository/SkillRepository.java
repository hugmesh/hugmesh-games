package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.Skill;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface SkillRepository extends ReactiveCrudRepository<Skill, Long> {}
