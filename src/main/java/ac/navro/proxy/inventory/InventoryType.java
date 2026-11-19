package ac.navro.proxy.inventory;


public enum InventoryType {

    CHEST(27, "Chest", 0),
    ENDER_CHEST(27, "Ender Chest", 0),
    DOUBLE_CHEST(54, "Double Chest", 0),
    PLAYER(40, "Player", -1),
    FURNACE(3, "Furnace", 2),
    BLAST_FURNACE(3, "Blast Furnace", 27),
    SMOKER(3, "Smoker", 28),
    CAMPFIRE(4, "Campfire", -1),
    CRAFTING(5, "Crafting", 1),
    WORKBENCH(10, "Crafting", 1),
    BREWING_STAND(5, "Brewing", 4),
    ANVIL(3, "Anvil", 5),
    ENCHANT_TABLE(2, "Enchantment Table", 3),
    DISPENSER(9, "Dispenser", 6),
    DROPPER(9, "Dropper", 7),
    HOPPER(5, "Hopper", 8),
    UI(1, "UI", -1),
    SHULKER_BOX(27, "Shulker Box", 0),
    BEACON(1, "Beacon", 13),
    LOOM(4, "Loom", 24),
    CHEST_BOAT(27, "Chest Boat", 0),
    ENTITY_ARMOR(5, "Entity Armor", -1),
    ENTITY_EQUIPMENT(36, "Entity Equipment", -1),
    MINECART_CHEST(27, "Minecart with Chest", 0),
    MINECART_HOPPER(5, "Minecart with Hopper", 8),
    OFFHAND(1, "Offhand", -1),
    TRADING(3, "Villager Trade", 15),
    BARREL(27, "Barrel", 0),
    SMITHING_TABLE(3, "Smithing Table", 33),
    GRINDSTONE(3, "Grindstone", 26),
    STONECUTTER(2, "Stonecutter", 29),
    CARTOGRAPHY(2, "Cartography Table", 30),
    HORSE(17, "Horse", 12),
    CRAFTER(9, "Crafter", 36),
    COMMAND_BLOCK(0, "Command Block", 16);

    private final int size;
    private final String title;
    private final int typeId;

    InventoryType(int defaultSize, String defaultBlockEntity, int typeId) {
        this.size = defaultSize;
        this.title = defaultBlockEntity;
        this.typeId = typeId;
    }

    public int getDefaultSize() {
        return size;
    }

    public String getDefaultTitle() {
        return title;
    }

    public int getNetworkType() {
        return typeId;
    }

    public static InventoryType from(int id) {
        for (InventoryType type : values()) {
            if (type.getNetworkType() == id) {
                return type;
            }
        }
        return null;
    }
}
