package ac.navro.proxy;

import org.cloudburstmc.protocol.bedrock.codec.BaseBedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class ProtocolDefinitions {
    private final Map<Integer, ItemDefinition> itemsById = new HashMap<>();
    private final Map<String, ItemDefinition> itemsByName = new HashMap<>();
    private final Map<Integer, BlockDefinition> blocksById = new HashMap<>();

    private final DefinitionRegistry<ItemDefinition> items = new DefinitionRegistry<>() {
        @Override public ItemDefinition getDefinition(int id) { return itemsById.get(id); }
        @Override public ItemDefinition getDefinition(String name) { return itemsByName.get(name); }
        @Override public boolean isRegistered(ItemDefinition value) {
            return value != null && itemsById.get(value.getRuntimeId()) == value;
        }
    };

    private final DefinitionRegistry<BlockDefinition> blocks = new DefinitionRegistry<>() {
        @Override public BlockDefinition getDefinition(int id) {


            return blocksById.computeIfAbsent(id, WireBlockDefinition::new);
        }
        @Override public boolean isRegistered(BlockDefinition value) {
            return value != null && blocksById.get(value.getRuntimeId()) == value;
        }
    };


    public BlockDefinition blockDefinition(int networkId) {
        return blocks.getDefinition(networkId);
    }

    public void configure(BedrockCodecHelper helper, boolean readsBackend) {
        helper.setItemDefinitions(items);
        helper.setBlockDefinitions(blocks);
        if (readsBackend) {


            ((BaseBedrockCodecHelper) helper).setEncodingSettings(EncodingSettings.CLIENT);
        }
    }

    public void observeBackend(BedrockPacket packet) {
        if (packet instanceof StartGamePacket start && !start.getItemDefinitions().isEmpty()) {
            itemsById.clear();
            itemsByName.clear();
            register(start.getItemDefinitions());
        } else if (packet instanceof ItemComponentPacket component) {
            register(component.getItems());
        }
    }

    private void register(List<ItemDefinition> definitions) {
        for (ItemDefinition item : definitions) {
            ItemDefinition previous = itemsById.put(item.getRuntimeId(), item);
            if (previous != null) itemsByName.remove(previous.getIdentifier(), previous);
            itemsByName.put(item.getIdentifier(), item);
        }
    }

    private record WireBlockDefinition(int id) implements BlockDefinition {
        @Override public int getRuntimeId() { return id; }
    }
}
