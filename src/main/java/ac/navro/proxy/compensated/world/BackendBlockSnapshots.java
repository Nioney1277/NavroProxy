package ac.navro.proxy.compensated.world;

import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry.RuntimePalette;
import ac.navro.proxy.packets.server.ServerChunkPackets;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.*;





public final class BackendBlockSnapshots {
    public record Blocks(int layerZero, int layerOne) { }
    private final CompensatedWorld world = new CompensatedWorld();
    private final ServerChunkPackets packets = new ServerChunkPackets(new WirePlayerState(), world);
    private long generation;

    public void setProtocolVersion(int protocol) { world.setProtocolVersion(protocol); }
    public void setRuntimeBlockPalette(RuntimePalette palette) { world.setRuntimeBlockPalette(palette); }
    public long generation() { return generation; }

    public void observe(BedrockPacket packet) {
        if (packet instanceof StartGamePacket start) {
            generation++;
            world.clearChunks();
            world.apply(start);
        } else if (packet instanceof ChangeDimensionPacket change) {
            generation++;
            world.changeDimension(change.getDimension());
        } else {
            packets.onPacket(packet);
        }
    }

    public Blocks at(Vector3i position) {
        int x=position.getX(), y=position.getY(), z=position.getZ();
        if (y < world.getMinY() || y >= world.getHeightY() || !world.isSectionKnown(x,y,z)) return null;
        return new Blocks(world.getRawBlockAt(x,y,z,0), world.getRawBlockAt(x,y,z,1));
    }
}
