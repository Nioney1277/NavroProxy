package ac.navro.proxy.packets.server;

import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.ItemUtil;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry;
import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.*;
import ac.navro.proxy.diagnostics.WorldCollisionDiagnostics;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult;


public final class ServerChunkPackets {
    private final WirePlayerState player;
    private final CompensatedWorld world;
    private record SectionKey(int dimension,int x,int y,int z){}
    private record Cell(int x,int y,int z,int layer){}
    private record Overlay(BlockUpdate update,long revision){}
    private static final class Pending {
        final long hash,revision,epoch;final Map<Cell,Overlay> overlays=new LinkedHashMap<>();
        Pending(long hash,long revision,long epoch){this.hash=hash;this.revision=revision;this.epoch=epoch;}
    }
    private final LinkedHashMap<Long,byte[]> blobs=new LinkedHashMap<>(16,.75F,true);
    private final LinkedHashMap<SectionKey,Pending> pending=new LinkedHashMap<>();
    private int blobBytes,overlayCount;
    private static final int MAX_BLOB_BYTES=16*1024*1024,MAX_PENDING=4096;
    private void trace(String event,Object... values){var log=player.worldDiagnostics();if(log!=null)log.event(event,WorldCollisionDiagnostics.fields(values));}
    private void queueAck(BedrockPacket packet,Map<String,Object> fields,Runnable task){
        String kind=packet.getClass().getSimpleName();fields.put("packet",kind);fields.put("encoderAttached",player.getEntityBatchEncoder()!=null);
        var log=player.worldDiagnostics();long id=log==null?-1:log.event("world_queued",fields);
        player.queueAcknowledgment(packet,()->{
            trace("world_ack","worldEvent",id,"packet",kind,"ackId",player.getLatencyUtil().currentAcknowledgmentId());
            try{task.run();}catch(RuntimeException|Error error){trace("world_task_error","worldEvent",id,"error",error.toString());throw error;}
        });
    }
    private void acceptBlob(long hash,byte[] bytes){
        if(bytes.length>MAX_BLOB_BYTES)return;
        byte[] old=blobs.put(hash,bytes);if(old!=null)blobBytes-=old.length;blobBytes+=bytes.length;
        for(var waiting:new ArrayList<>(pending.entrySet()))if(waiting.getValue().hash==hash)resolvePending(waiting.getKey(),waiting.getValue(),bytes);
        while(blobBytes>MAX_BLOB_BYTES){var first=blobs.entrySet().iterator().next();blobBytes-=first.getValue().length;blobs.remove(first.getKey());}
    }
    private void removePending(SectionKey key){Pending removed=pending.remove(key);if(removed!=null)overlayCount-=removed.overlays.size();}
    private void clearPendingColumn(int x,int z){for(var key:new ArrayList<>(pending.keySet()))if(key.x()==x&&key.z()==z)removePending(key);}
    private void cleanupPending(){for(var entry:new ArrayList<>(pending.entrySet()))if(entry.getKey().dimension()!=world.dimension()||entry.getValue().epoch!=world.getBlockBreakEpoch())removePending(entry.getKey());}


    public ServerChunkPackets(WirePlayerState player, CompensatedWorld world) {
        this.player = player;
        this.world = world;
    }

    public void onPacket(BedrockPacket packet) {
        if (packet instanceof DimensionDataPacket dimensions) {

            world.apply(dimensions);trace("world_registry","kind","DimensionData");
        } else if (packet instanceof ChunkRadiusUpdatedPacket radius) {
            final int value = radius.getRadius();
            queueAck(packet,WorldCollisionDiagnostics.fields("radius",value), () -> world.setRadius(value));
        } else if (packet instanceof NetworkChunkPublisherUpdatePacket publisher) {
            final Vector3i position = publisher.getPosition();
            final int radius = publisher.getRadius();
            queueAck(packet,WorldCollisionDiagnostics.fields("center",String.valueOf(position),"radius",radius), () -> {
                world.setRadiusCenter(position);
                world.setRadius(radius);


            });
        } else if(packet instanceof ClientCacheMissResponsePacket response){
            var snapshots=new LinkedHashMap<Long,byte[]>();
            for(var entry:response.getBlobs().long2ObjectEntrySet()){
                ByteBuf data=entry.getValue();if(data.readableBytes()>MAX_BLOB_BYTES){trace("blob_oversize","hash",entry.getLongKey(),"bytes",data.readableBytes());continue;}
                byte[] bytes=new byte[data.readableBytes()];data.getBytes(data.readerIndex(),bytes);snapshots.put(entry.getLongKey(),bytes);
            }
            queueAck(packet,WorldCollisionDiagnostics.fields("blobs",snapshots.size()),()->{
                cleanupPending();for(var entry:snapshots.entrySet())acceptBlob(entry.getKey(),entry.getValue());
            });
        } else if (packet instanceof LevelChunkPacket chunk) {
            final int dimension = world.protocolVersion() >= 649 ? chunk.getDimension() : world.dimension();
            if (dimension < 0 || dimension > 2) return;
            final long revision=world.bucketPredictionRevision();final int x=chunk.getChunkX(),z=chunk.getChunkZ();
            if(chunk.isRequestSubChunks()){
                queueAck(packet,WorldCollisionDiagnostics.fields("dimension",dimension,"chunk",List.of(x,z),"partial",true),()->{
                    if(dimension!=world.dimension()){trace("world_discard_dimension","dimension",dimension);return;}
                    cleanupPending();world.ensurePartialColumn(x,z);trace("column_announced","chunk",List.of(x,z));
                });return;
            }
            final int count=chunk.getSubChunksLength();
            final CompensatedWorld.CachedChunk snapshot;
            final long[] hashes=chunk.getBlobIds().toLongArray();
            if(chunk.isCachingEnabled()){
                snapshot=CompensatedWorld.CachedChunk.network(world.minY(dimension),world.maxY(dimension),(world.maxY(dimension)-world.minY(dimension))>>4,world.airNetworkId());
                for(int i=0;i<Math.max(0,count);i++)snapshot.invalidateSection(i);
            }else snapshot=readLevelChunk(chunk,world.minY(dimension),world.maxY(dimension),world.airNetworkId(),error->trace("level_chunk_parse_error","chunk",List.of(x,z),"error",error));
            queueAck(packet,WorldCollisionDiagnostics.fields("dimension",dimension,"chunk",List.of(x,z),"sections",count,"cached",chunk.isCachingEnabled(),"sentRevision",revision),()->{
                if(dimension!=world.dimension()){trace("world_discard_dimension","dimension",dimension);return;}
                cleanupPending();clearPendingColumn(x,z);world.putAcknowledgedChunk(x,z,snapshot,revision);
                trace("chunk_applied","chunk",List.of(x,z),"sections",count,"sentRevision",revision);
                for(int i=0;i<Math.max(0,count)&&i<hashes.length;i++){
                    if(!snapshot.isSectionKnown(i))section(new SectionKey(dimension,x,(world.getMinY()>>4)+i,z),null,hashes[i],revision);
                }
            });
        } else if(packet instanceof SubChunkPacket subChunks){
            final int dimension=subChunks.getDimension();final var center=subChunks.getCenterPosition();
            if(center==null||dimension<0||dimension>2)return;
            final long revision=world.bucketPredictionRevision();
            record Snapshot(SectionKey key,int[][] layers,Long hash,byte[] inline,SubChunkRequestResult result){}
            var snapshots=new ArrayList<Snapshot>();
            for(var data:subChunks.getSubChunks()){
                if(data.getPosition()==null)continue;
                final SectionKey key;
                try{key=new SectionKey(dimension,Math.addExact(center.getX(),data.getPosition().getX()),Math.addExact(center.getY(),data.getPosition().getY()),Math.addExact(center.getZ(),data.getPosition().getZ()));}
                catch(ArithmeticException invalid){trace("subchunk_invalid_position","center",center.toString());continue;}
                if(data.getResult()!=SubChunkRequestResult.SUCCESS&&data.getResult()!=SubChunkRequestResult.SUCCESS_ALL_AIR){trace("subchunk_result","section",List.of(key.x(),key.y(),key.z()),"result",String.valueOf(data.getResult()));continue;}
                int[][] layers=data.getResult()==SubChunkRequestResult.SUCCESS_ALL_AIR?airSection(world.airNetworkId()):readSection(data.getData(),key.y());
                byte[] inline=null;
                if(data.getResult()==SubChunkRequestResult.SUCCESS&&layers!=null&&data.getBlobId()!=null&&data.getData()!=null&&data.getData().isReadable()&&data.getData().readableBytes()<=MAX_BLOB_BYTES){inline=new byte[data.getData().readableBytes()];data.getData().getBytes(data.getData().readerIndex(),inline);}
                snapshots.add(new Snapshot(key,layers,data.getBlobId(),inline,data.getResult()));
            }
            queueAck(packet,WorldCollisionDiagnostics.fields("dimension",dimension,"sectionCount",snapshots.size(),"sections",snapshots.stream().limit(64).map(snap->WorldCollisionDiagnostics.fields("position",List.of(snap.key().x(),snap.key().y(),snap.key().z()),"result",snap.result().toString(),"inlineKnown",snap.layers()!=null,"blob",snap.hash())).toList(),"sentRevision",revision),()->{
                if(dimension!=world.dimension()){trace("world_discard_dimension","dimension",dimension);return;}
                cleanupPending();for(var snapshot:snapshots){if(snapshot.inline()!=null)acceptBlob(snapshot.hash(),snapshot.inline());section(snapshot.key(),snapshot.layers(),snapshot.hash(),revision);}
            });
        } else if (packet instanceof UpdateBlockPacket update) {
            if (update.getBlockPosition() == null || update.getDefinition() == null) return;
            final Vector3i position = update.getBlockPosition();
            queueUpdates(packet, List.of(new BlockUpdate(position.getX(), position.getY(), position.getZ(),
                    update.getDataLayer(), update.getDefinition().getRuntimeId())));
        } else if (packet instanceof UpdateSubChunkBlocksPacket update) {
            final List<BlockUpdate> updates = new ArrayList<>();
            collectUpdates(update.getStandardBlocks(), 0, updates);
            collectUpdates(update.getExtraBlocks(), 1, updates);
            queueUpdates(packet, updates);
        } else if (packet instanceof BlockEventPacket event) {
            final Vector3i position = event.getBlockPosition();
            final int type = event.getEventType(), data = event.getEventData();
            if (position != null && type == 1) queueAck(packet,WorldCollisionDiagnostics.fields("position",position.toString(),"type",type,"data",data), () ->
                    world.handleCollisionBlockEvent(position.getX(), position.getY(), position.getZ(), type, data));
        } else if (packet instanceof BlockEntityDataPacket entity) {
            final Vector3i position = entity.getBlockPosition();
            final var tag = ItemUtil.copyTag(entity.getData());
            if (position != null && tag != null) queueAck(packet,WorldCollisionDiagnostics.fields("position",position.toString()), () ->
                    world.updateBlockEntityTag(position.getX(), position.getY(), position.getZ(), tag));
        }
    }

    private void queueUpdates(BedrockPacket packet, List<BlockUpdate> updates) {
        final long revision = world.bucketPredictionRevision();
        final int dimension = world.dimension();
        queueAck(packet,WorldCollisionDiagnostics.fields("dimension",dimension,"updateCount",updates.size(),"updates",updates.stream().limit(64).map(update->List.of(update.x,update.y,update.z,update.layer,update.networkId)).toList(),"sentRevision",revision), () -> {
            if(dimension!=world.dimension()){trace("world_discard_dimension","dimension",dimension);return;}
            cleanupPending();
            for(BlockUpdate update:updates){
                var key=new SectionKey(dimension,update.x>>4,update.y>>4,update.z>>4);Pending waiting=pending.get(key);
                if(waiting!=null){var cell=new Cell(update.x,update.y,update.z,update.layer);if(!waiting.overlays.containsKey(cell))overlayCount++;waiting.overlays.put(cell,new Overlay(update,revision));}
                int previous=world.getRawBlockAt(update.x,update.y,update.z,update.layer);
                boolean applied=world.applyAcknowledgedBlock(update.x,update.y,update.z,update.layer,update.networkId,revision);
                trace("block_applied","position",List.of(update.x,update.y,update.z),"layer",update.layer,"from",previous,"to",update.networkId,"accepted",applied,"stored",world.getRawBlockAt(update.x,update.y,update.z,update.layer),"sentRevision",revision);
                while(overlayCount>4096&&!pending.isEmpty()){var oldest=pending.keySet().iterator().next();removePending(oldest);trace("subchunk_pending_evicted","section",oldest.toString(),"reason","overlay_limit");}
            }
        });
    }

    private static int[][] airSection(int air){int[][] layers=new int[2][4096];for(int[] layer:layers)Arrays.fill(layer,air);return layers;}
    private int[][] readSection(ByteBuf bytes,int expectedY){
        if(bytes==null||!bytes.isReadable())return null;
        try{
            ByteBuf data=bytes.duplicate();int version=data.readUnsignedByte();if(version!=8&&version!=9)throw new IllegalArgumentException("Unknown section format "+version);
            int count=data.readUnsignedByte();if(version==9&&data.readByte()!=expectedY)throw new IllegalArgumentException("Section Y does not match packet position");
            int[][] layers=new int[count][];for(int i=0;i<count;i++)layers[i]=readLayer(data,world.airNetworkId());return layers;
        }catch(RuntimeException invalid){trace("subchunk_parse_error","sectionY",expectedY,"bytes",bytes.readableBytes(),"error",invalid.toString());return null;}
    }
    private int[][] readBlob(byte[] bytes,int y){ByteBuf data=Unpooled.wrappedBuffer(bytes);try{return readSection(data,y);}finally{data.release();}}
    private void section(SectionKey key,int[][] layers,Long hash,long revision){
        if(key.y()<(world.getMinY()>>4)||key.y()>=(world.getHeightY()>>4)){trace("subchunk_out_of_bounds","section",key.toString());return;}
        removePending(key);
        if(layers==null&&hash!=null){byte[] data=blobs.get(hash);if(data!=null)layers=readBlob(data,key.y());}
        boolean applied=world.applyAcknowledgedSection(key.x(),key.y(),key.z(),layers,revision);
        trace("subchunk_applied","section",List.of(key.x(),key.y(),key.z()),"known",layers!=null,"accepted",applied,"blob",hash,"sentRevision",revision);
        if(layers==null&&hash!=null){pending.put(key,new Pending(hash,revision,world.getBlockBreakEpoch()));while(pending.size()>MAX_PENDING){var first=pending.keySet().iterator().next();removePending(first);trace("subchunk_pending_evicted","section",first.toString(),"reason","pending_limit");}}
    }
    private void resolvePending(SectionKey key,Pending waiting,byte[] data){
        int[][] layers=readBlob(data,key.y());if(layers==null){trace("blob_invalid","blob",waiting.hash,"section",key.toString());return;}
        world.applyAcknowledgedSection(key.x(),key.y(),key.z(),layers,waiting.revision);
        for(var overlay:waiting.overlays.values()){var u=overlay.update();world.applyAcknowledgedBlock(u.x,u.y,u.z,u.layer,u.networkId,overlay.revision());}
        trace("subchunk_blob_resolved","section",List.of(key.x(),key.y(),key.z()),"blob",waiting.hash,"overlays",waiting.overlays.size());removePending(key);
    }

    private static void collectUpdates(List<BlockChangeEntry> entries, int layer, List<BlockUpdate> output) {
        for (BlockChangeEntry entry : entries) {
            final Vector3i position = entry.getPosition();

            output.add(new BlockUpdate(position.getX(), position.getY(), position.getZ(), layer,
                    entry.getDefinition().getRuntimeId()));
        }
    }

    public static CompensatedWorld.CachedChunk readLevelChunk(LevelChunkPacket packet, int dimension, int air) {
        final int minY = CompensatedWorld.minYForDimension(dimension);
        final int maxY = CompensatedWorld.maxYForDimension(dimension);
        return readLevelChunk(packet, minY, maxY, air);
    }

    public static CompensatedWorld.CachedChunk readLevelChunk(LevelChunkPacket packet, int minY, int maxY, int air) {
        return readLevelChunk(packet,minY,maxY,air,error->{});
    }
    private static CompensatedWorld.CachedChunk readLevelChunk(LevelChunkPacket packet,int minY,int maxY,int air,java.util.function.Consumer<String> errors){
        final CompensatedWorld.CachedChunk chunk = CompensatedWorld.CachedChunk.network(
                minY, maxY, (maxY - minY) >> 4, air);
        if (packet.getData() == null || !packet.getData().isReadable() || packet.getSubChunksLength() <= 0
                || packet.isRequestSubChunks()) return chunk;
        final ByteBuf stream = packet.getData().duplicate();
        try {
            for (int sectionY = 0; sectionY < packet.getSubChunksLength(); sectionY++) {
                final int formatVersion = stream.readUnsignedByte();
                final int layerCount = stream.readUnsignedByte();
                final int cacheSection = formatVersion >= 9 ? stream.readByte() - (minY >> 4) : sectionY;
                final int[][] layers = new int[layerCount][];
                for (int layer = 0; layer < layerCount; layer++) layers[layer] = readLayer(stream, air);
                chunk.setNetworkSection(cacheSection, layers);
            }
        } catch (RuntimeException ignored) {
            errors.accept(ignored.toString());

        }
        return chunk;
    }

    private static int[] readLayer(ByteBuf stream, int air) {
        final int versionId = stream.readUnsignedByte() >> 1;
        if (versionId == 127) return null;
        final int entriesPerWord = switch (versionId) {
            case 0 -> 0;
            case 1 -> 32;
            case 2 -> 16;
            case 3 -> 10;
            case 4 -> 8;
            case 5 -> 6;
            case 6 -> 5;
            case 8 -> 4;
            case 16 -> 2;
            default -> throw new IllegalArgumentException("Unsupported bit array version: " + versionId);
        };
        final int[] words = new int[versionId == 0 ? 0 : (4096 + entriesPerWord - 1) / entriesPerWord];
        for (int i = 0; i < words.length; i++) words[i] = stream.readIntLE();
        final int paletteSize = versionId == 0 ? 1 : VarInts.readInt(stream);
        if (paletteSize > stream.readableBytes()) throw new IllegalArgumentException("Truncated block palette");
        final int[] palette = new int[Math.max(1, paletteSize)];
        for (int i = 0; i < paletteSize; i++) palette[i] = VarInts.readInt(stream);
        if (paletteSize == 0) palette[0] = air;
        final int[] blocks = new int[4096];
        for (int i = 0; i < blocks.length; i++) {
            final int paletteIndex = versionId == 0 ? 0
                    : (words[i / entriesPerWord] >>> ((i % entriesPerWord) * versionId)) & ((1 << versionId) - 1);
            blocks[i] = paletteIndex >= 0 && paletteIndex < palette.length ? palette[paletteIndex] : air;
        }
        return blocks;
    }

    public static boolean finishLoadingScreen(WirePlayerState player, ServerboundLoadingScreenPacket packet) {
        if (packet.getType() != ServerboundLoadingScreenPacketType.END_LOADING_SCREEN
                || !player.inLoadingScreen
                || !Objects.equals(player.currentLoadingScreen, packet.getLoadingScreenId())) return false;
        player.currentLoadingScreen = null;
        player.inLoadingScreen = false;
        player.sinceLoadingScreen = 0;
        return true;
    }

    private record BlockUpdate(int x, int y, int z, int layer, int networkId) { }
}
