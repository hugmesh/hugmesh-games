package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("scene_elements")
public class SceneElement {

    @Id
    private Long id;
    private Long chapterId;
    private String elementType;
    private String name;
    private Float positionX;
    private Float positionY;
    private Float positionZ;
    private Float rotationX;
    private Float rotationY;
    private Float rotationZ;
    private String properties;
    private Integer sortOrder;

    public Long getId() { return id; }
    public Long getChapterId() { return chapterId; }
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
}
