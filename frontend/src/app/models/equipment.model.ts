export interface Item {
  id: number;
  name: string;
  description: string;
  itemType: string;
  slot: string;
  rarity: string;
  statModifiers: Record<string, number>;
  iconKey: string;
}

export interface EquipmentSlot {
  slot: string;
  item: Item | null;
}
