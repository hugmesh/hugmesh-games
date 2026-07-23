package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.Skill;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface SkillRepository extends ReactiveCrudRepository<Skill, Long> {}
