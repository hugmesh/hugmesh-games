package com.threewizards.repository;

import com.threewizards.model.Quest;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface QuestRepository extends ReactiveCrudRepository<Quest, Long> {}
