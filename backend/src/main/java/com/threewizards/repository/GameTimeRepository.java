package com.threewizards.repository;

import com.threewizards.model.GameTimeState;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface GameTimeRepository extends ReactiveCrudRepository<GameTimeState, Long> {}
