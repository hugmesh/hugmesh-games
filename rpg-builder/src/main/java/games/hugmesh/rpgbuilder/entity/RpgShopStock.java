package games.hugmesh.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * One item line in a shop-kind building's stock list. Prices are
 * server-side (the generated shop UI never trusts client-supplied
 * prices). {@code stockQty == -1} is the documented "unlimited"
 * sentinel; the DB enforces {@code buy_price > 0},
 * {@code sell_price >= 0} and {@code sell_price <= buy_price} (no
 * infinite-gold buy-low/sell-high loops).
 */
@Table("rpg_shop_stock")
public class RpgShopStock {
    @Id
    private UUID id;
    @Column("project_id")
    private UUID projectId;
    @Column("building_id")
    private UUID buildingId;
    @Column("item_id")
    private UUID itemId;
    @Column("buy_price")
    private Integer buyPrice;
    @Column("sell_price")
    private Integer sellPrice;
    @Column("stock_qty")
    private Integer stockQty;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID getBuildingId() { return buildingId; }
    public void setBuildingId(UUID buildingId) { this.buildingId = buildingId; }
    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }
    public Integer getBuyPrice() { return buyPrice; }
    public void setBuyPrice(Integer buyPrice) { this.buyPrice = buyPrice; }
    public Integer getSellPrice() { return sellPrice; }
    public void setSellPrice(Integer sellPrice) { this.sellPrice = sellPrice; }
    public Integer getStockQty() { return stockQty; }
    public void setStockQty(Integer stockQty) { this.stockQty = stockQty; }
}
