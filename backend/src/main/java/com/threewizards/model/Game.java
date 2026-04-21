package com.threewizards.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "games")
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, unique = true, length = 50)
    private String slug;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "scene_config", columnDefinition = "TEXT")
    private String sceneConfig;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSlug() { return slug; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public String getSceneConfig() { return sceneConfig; }
    public Instant getCreatedAt() { return createdAt; }
}
