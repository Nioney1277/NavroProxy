package ac.navro.proxy.compensated.cache.container;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.data.inventory.ItemCache;
import ac.navro.proxy.inventory.InventoryType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.math.vector.Vector3i;

import java.util.Arrays;

public class ContainerCache {
    public static final byte OFFHAND = (byte) 119;
    public static final byte UI = (byte) 124;

    protected final CompensatedInventory inventory;

    private final byte id;
    private final InventoryType type;
    private final Vector3i blockPosition;
    private final long uniqueEntityId;

    private final int containerSize;
    private final int offset;
    private final ItemCache[] contents;

    public ContainerCache(CompensatedInventory inventory, byte id, InventoryType type,
                          Vector3i blockPosition, long uniqueEntityId) {
        this.inventory = inventory;
        this.id = id;
        this.type = type;
        this.blockPosition = blockPosition;
        this.uniqueEntityId = uniqueEntityId;
        this.offset = offsetFor(type);
        this.containerSize = id == UI ? 54 : sizeFor(type);




        this.contents = new ItemCache[Math.max(0, this.containerSize)];
        Arrays.fill(this.contents, ItemCache.AIR);
    }

    public static int offsetFor(final InventoryType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case ENCHANT_TABLE -> 14;
            case LOOM -> 9;
            case WORKBENCH -> 32;
            case BEACON -> 27;
            case ANVIL -> 1;
            case STONECUTTER -> 3;
            case CARTOGRAPHY -> 12;
            case SMITHING_TABLE -> 51;
            case GRINDSTONE -> 16;
            case TRADING -> 4;
            default -> 0;
        };
    }

    public static int sizeFor(final InventoryType type) {
        if (type == null) {
            return 36;
        }
        return switch (type) {
            case FURNACE, BLAST_FURNACE, SMOKER, LOOM, SMITHING_TABLE -> 3;
            case BREWING_STAND, HOPPER, MINECART_HOPPER -> 5;
            case DROPPER, DISPENSER, WORKBENCH, CRAFTER -> 9;
            case ENCHANT_TABLE, ANVIL, HORSE, CARTOGRAPHY, GRINDSTONE -> 2;
            case BEACON, STONECUTTER -> 1;
            case COMMAND_BLOCK -> 0;
            case MINECART_CHEST, CHEST_BOAT -> 26;
            case CHEST, ENDER_CHEST, DOUBLE_CHEST, SHULKER_BOX, BARREL -> 56;
            case TRADING -> 2;
            case ENTITY_ARMOR -> 4;
            default -> 36;
        };
    }

    public byte getId() { return id; }
    public InventoryType getType() { return type; }
    public Vector3i getBlockPosition() { return blockPosition; }
    public long getUniqueEntityId() { return uniqueEntityId; }
    public int getOffset() { return offset; }
    public ItemCache[] getContents() { return contents; }

    public int getContainerSize() {
        return this.containerSize + this.offset;
    }

    public ItemCache get(final int slot) {
        final int index = slot - this.offset;
        if (this.contents == null || index < 0 || index >= this.contents.length) {
            return ItemCache.AIR;
        }

        final ItemCache cache = this.contents[index];
        return cache == null ? ItemCache.AIR : cache;
    }

    public void set(final int slot, final ItemData raw) {
        this.set(slot, raw, true);
    }

    public void set(final int slot, final ItemCache raw) {
        this.set(slot, raw, true);
    }

    public void set(final int slot, final ItemData raw, final boolean offset) {
        this.set(slot, ItemCache.build(this.inventory, raw), offset);
    }

    public void set(final int slot, final ItemCache cache, final boolean offset) {
        final int index = offset ? slot - this.offset : slot;
        if (this.contents == null || index < 0 || index >= this.contents.length) {
            return;
        }

        this.contents[index] = cache == null ? ItemCache.AIR : cache;
    }
}
