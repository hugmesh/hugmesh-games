package com.threewizards.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "scene_elements")
public class SceneElement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    @JsonIgnore
    private Game game;

    @Column(name = "element_type", nullable = false, length = 50)
    private String elementType;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "position_x")
    private Float positionX;

    @Column(name = "position_y")
    private Float positionY;

    @Column(name = "position_z")
    private Float positionZ;

    @Column(name = "rotation_x")
    private Float rotationX;

    @Column(name = "rotation_y")
    private Float rotationY;

    @Column(name = "rotation_z")
    private Float rotationZ;

    @Column(columnDefinition = "TEXT")
    private String properties;

    @Column(name = "sort_order")
    private Integer sortOrder;

    public Long getId() { return id; }
    public String getElementType() { return elementType; }
    public String getName() { return name; }
    public Float getPositionX() { return positionX; }
    public Float getPositionY() { return positionY; }
    public Float getPositionZ() { return positionZ; }
    public Float getRotationX() { return rotationX; }
    public Float getRotationY() { return rotationY; }
    public Float getRotationZ() { return rotationZ; }
    public String getProperties() { return properties; }
    public Integer getSortOrder() { return sortOrder; }
    public Game getGame() { return game; }
}
