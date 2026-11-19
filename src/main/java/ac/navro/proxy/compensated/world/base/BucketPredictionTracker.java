package ac.navro.proxy.compensated.world.base;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;


final class BucketPredictionTracker {
    private long revision;
    private final Map<BlockKey, Prediction> blocks = new HashMap<>();

    record BlockKey(int x, int y, int z, int layer) { }
    enum State { LOCAL, BREAK_PENDING, BREAK_CONFIRMED }

    record Prediction(long revision, int networkId, State state) {
        boolean isBreak() {
            return state != State.LOCAL;
        }
    }

    long revision() {
        return revision;
    }

    void predict(int x, int y, int z, int layer, int networkId) {
        predict(x, y, z, layer, networkId, State.LOCAL);
    }

    void predictBreak(int x, int y, int z, int layer, int networkId) {
        predict(x, y, z, layer, networkId, State.BREAK_PENDING);
    }

    private void predict(int x, int y, int z, int layer, int networkId, State state) {
        blocks.put(new BlockKey(x, y, z, layer), new Prediction(++revision, networkId, state));
    }

    boolean isPredictedBreak(int x, int y, int z, int layer, int currentNetworkId) {
        final Prediction prediction = blocks.get(new BlockKey(x, y, z, layer));
        return prediction != null && prediction.isBreak() && prediction.networkId() == currentNetworkId;
    }


    void beginBreak(int x, int y, int z, int layer) {
        final BlockKey key = new BlockKey(x, y, z, layer);
        final Prediction prediction = blocks.get(key);
        if (prediction != null && prediction.isBreak()) {
            blocks.put(key, new Prediction(prediction.revision(), prediction.networkId(), State.LOCAL));
        }
    }


    void acknowledge(int x, int y, int z, int layer, int networkId, long sentRevision) {
        final BlockKey key = new BlockKey(x, y, z, layer);
        final Prediction prediction = blocks.get(key);
        if (prediction != null && prediction.isBreak() && prediction.revision() <= sentRevision) {
            blocks.put(key, settled(prediction, networkId));
        }
    }

    void acknowledgeChunk(int chunkX, int chunkZ, long sentRevision, ToIntFunction<BlockKey> snapshot) {
        acknowledgeRegion(chunkX, chunkZ, null, sentRevision, snapshot);
    }
    void acknowledgeSection(int chunkX,int sectionY,int chunkZ,long sentRevision,ToIntFunction<BlockKey> snapshot){
        acknowledgeRegion(chunkX,chunkZ,sectionY,sentRevision,snapshot);
    }
    private void acknowledgeRegion(int chunkX,int chunkZ,Integer sectionY,long sentRevision,ToIntFunction<BlockKey> snapshot){
        for (final var entry : blocks.entrySet()) {
            final BlockKey key = entry.getKey();
            final Prediction prediction = entry.getValue();
            if ((key.x() >> 4) == chunkX && (key.z() >> 4) == chunkZ
                    && (sectionY==null || (key.y()>>4)==sectionY)
                    && prediction.isBreak() && prediction.revision() <= sentRevision) {
                entry.setValue(settled(prediction, snapshot.applyAsInt(key)));
            }
        }
    }

    private static Prediction settled(Prediction prediction, int networkId) {
        return new Prediction(prediction.revision(), networkId,
                prediction.networkId() == networkId ? State.BREAK_CONFIRMED : State.LOCAL);
    }


    void observeBlock(int x, int y, int z, int layer, int networkId) {
        final BlockKey key = new BlockKey(x, y, z, layer);
        final Prediction prediction = blocks.get(key);
        if (prediction != null && prediction.isBreak() && prediction.networkId() != networkId) {
            blocks.put(key, new Prediction(prediction.revision(), networkId, State.LOCAL));
        }
    }

    void observeChunk(int chunkX, int chunkZ, ToIntFunction<BlockKey> snapshot) {
        observeRegion(chunkX,chunkZ,null,snapshot);
    }
    void observeSection(int chunkX,int sectionY,int chunkZ,ToIntFunction<BlockKey> snapshot){observeRegion(chunkX,chunkZ,sectionY,snapshot);}
    private void observeRegion(int chunkX,int chunkZ,Integer sectionY,ToIntFunction<BlockKey> snapshot){
        for (final var entry : blocks.entrySet()) {
            final BlockKey key = entry.getKey();
            final Prediction prediction = entry.getValue();
            if ((key.x() >> 4) == chunkX && (key.z() >> 4) == chunkZ && (sectionY==null || (key.y()>>4)==sectionY) && prediction.isBreak()) {
                final int networkId = snapshot.applyAsInt(key);
                if (networkId != prediction.networkId()) {
                    entry.setValue(new Prediction(prediction.revision(), networkId, State.LOCAL));
                }
            }
        }
    }

    boolean superseded(int x, int y, int z, int layer, long sentRevision) {
        final Prediction prediction = blocks.get(new BlockKey(x, y, z, layer));
        return prediction != null && prediction.revision() > sentRevision;
    }

    Map<BlockKey, Prediction> blocks() {
        return blocks;
    }

    void retainChunks(BiPredicate<Integer, Integer> retained) {
        blocks.keySet().removeIf(key -> !retained.test(key.x() >> 4, key.z() >> 4));
    }

    void clear() {
        blocks.clear();

    }
}
