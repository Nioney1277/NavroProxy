package ac.navro.proxy.prediction.fluid;

import ac.navro.proxy.data.FluidState;
import ac.navro.proxy.data.FluidState.Type;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.math.vector.Vector3i;
import java.util.ArrayList;
import java.util.List;


public final class LiquidPhysics {
    public static final float WATER_PUSH = 0.014F;
    public static final float LAVA_PUSH = 0.0035F;
    private static final float MIN_FLOW_LENGTH_SQUARED = 0.0001F * 0.0001F;
    private LiquidPhysics() { }

    public interface WorldAccess {
        boolean hasChunksAt(int minX, int minZ, int maxX, int maxZ);
        FluidState fluidAt(int x, int y, int z);
        boolean isAirAt(int x, int y, int z);
        Vec3 flowAt(int x, int y, int z, FluidState state);
    }

    public static Result sample(CompensatedWorld world, Box actor, boolean flying, int dimension) {
        return sampleLiquids(actor, flying, new WorldAccess() {
            public boolean hasChunksAt(int minX,int minZ,int maxX,int maxZ) {
                return world.hasChunksAt(minX,minZ,maxX,maxZ,dimension);
            }
            public FluidState fluidAt(int x,int y,int z) { return world.fluidAt(x,y,z,dimension); }
            public boolean isAirAt(int x,int y,int z) { return world.isAirAt(x,y,z,dimension); }
            public Vec3 flowAt(int x,int y,int z,FluidState fluid) { return world.flowAt(x,y,z,dimension); }
        });
    }

    public static Result sampleLiquids(Box actor, boolean flying, WorldAccess world) {
        final Sample lava = sample(actor, world, Type.LAVA, LAVA_PUSH, 0.1F, 0.4F);
        final Sample water = sample(actor, world, Type.WATER, WATER_PUSH, 0.001F, 0.401F);
        final Type selected = lava.touching() ? Type.LAVA
                : water.touching() ? Type.WATER : Type.EMPTY;
        final Vec3 push = flying ? Vec3.ZERO : selected == Type.LAVA ? lava.appliedPush()
                : selected == Type.WATER ? water.appliedPush() : Vec3.ZERO;
        return new Result(water, lava, selected, push);
    }

    private static Sample sample(Box actor, WorldAccess world, Type type,
                                 float strength, float horizontal, float vertical) {
        final Box region = actor.expand(1);
        if (!world.hasChunksAt((int) Math.floor(region.minX), (int) Math.floor(region.minZ),
                (int) Math.ceil(region.maxX), (int) Math.ceil(region.maxZ))) return Sample.EMPTY;
        final Box box = contractedQueryBox(actor, horizontal, vertical);
        final int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX + 1.0D);
        final int minY = (int) Math.floor(box.minY), maxY = (int) Math.floor(box.maxY + 1.0D);
        final int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ + 1.0D);
        boolean touching = false, downwardFlow = false, hasPositiveDepth = false;
        float highestSurface = -Float.MAX_VALUE;
        final List<Vector3i> cells = new ArrayList<>();
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    final FluidState fluid = world.fluidAt(x, y, z);
                    if (fluid.type() != type) continue;
                    touching = true;
                    highestSurface = Math.max(highestSurface, y + 1.0F);
                    cells.add(Vector3i.from(x, y, z));
                    hasPositiveDepth |= fluid.level() > 0
                            || positiveDepth(world, type, x - 1, y, z)
                            || positiveDepth(world, type, x + 1, y, z)
                            || positiveDepth(world, type, x, y, z - 1)
                            || positiveDepth(world, type, x, y, z + 1);
                }
            }
        }
        Vec3 accumulatedFlow = Vec3.ZERO;
        if (hasPositiveDepth) {
            for (Vector3i cell : cells) {
                final FluidState fluid = world.fluidAt(cell.getX(), cell.getY(), cell.getZ());
                final Vec3 flow = world.flowAt(cell.getX(), cell.getY(), cell.getZ(), fluid);
                accumulatedFlow = accumulatedFlow.add(flow);
                downwardFlow |= flow.y < 0;
            }
        }
        final float lengthSquared = accumulatedFlow.lengthSquared();
        final Vec3 applied = touching && lengthSquared >= MIN_FLOW_LENGTH_SQUARED
                ? accumulatedFlow.multiply(strength / (float) Math.sqrt(lengthSquared)) : Vec3.ZERO;
        return new Sample(touching, touching ? highestSurface : 0, downwardFlow,
                accumulatedFlow, applied, box);
    }

    private static boolean positiveDepth(WorldAccess world, Type type, int x, int y, int z) {
        final FluidState fluid = world.fluidAt(x, y, z);
        return fluid.type() == type && fluid.level() > 0;
    }

    static Box contractedQueryBox(Box source, float horizontal, float vertical) {
        float minX = source.minX + horizontal, maxX = source.maxX - horizontal;
        float minY = source.minY + vertical, maxY = source.maxY - vertical;
        float minZ = source.minZ + horizontal, maxZ = source.maxZ - horizontal;
        if (minX > maxX) minX = maxX = (source.minX + source.maxX) * 0.5F;
        if (minY > maxY) minY = maxY = (source.minY + source.maxY) * 0.5F;
        if (minZ > maxZ) minZ = maxZ = (source.minZ + source.maxZ) * 0.5F;
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public record Sample(boolean touching, float surfaceHeight, boolean downwardFlow,
                         Vec3 accumulatedFlow, Vec3 appliedPush, Box queryBox) {
        public static final Sample EMPTY = new Sample(false, 0, false, Vec3.ZERO, Vec3.ZERO, Box.EMPTY);
    }
    public record Result(Sample water, Sample lava, Type selectedType, Vec3 selectedPush) {
        public static final Result EMPTY = new Result(Sample.EMPTY, Sample.EMPTY, Type.EMPTY, Vec3.ZERO);
    }
}
