package com.threewizards.repository;

import com.threewizards.model.SceneElement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SceneElementRepository extends JpaRepository<SceneElement, Long> {
    List<SceneElement> findByGameIdOrderBySortOrder(Long gameId);
}
