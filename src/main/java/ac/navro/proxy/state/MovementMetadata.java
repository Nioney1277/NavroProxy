package ac.navro.proxy.state;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.proxy.prediction.fluid.FluidMovement;
import ac.navro.proxy.player.data.tracker.FlagTracker;
import ac.navro.proxy.util.EntityMetadataUtil;
import java.util.Set;


public final class MovementMetadata {
    private MovementMetadata() { }





    public interface Context<I> extends FluidMovement.Context, FlagTracker.Context<I> {
        FlagTracker<I> getFlagTracker();
        void setServerSprinting(boolean sprinting);
        I compensatedChest();
        boolean isNull(I item);
        int nativeItemId(I item);
        void stage(String name, Object... values);
    }

    public static <I> void applyClientVisibleMovementMetadata(Context<I> player, final Float width, final Float height, final Float scale,
                                                  final Set<Integer> flags,
                                                  boolean hasBaseFlags, boolean hasExtendedFlags) {
        player.stage("POSE_METADATA_BEGIN","width",width,"height",height,"scale",scale,
                "baseFlagsPresent",hasBaseFlags,"extendedFlagsPresent",hasExtendedFlags,
                "metadataFlags",flags == null ? null : flags.stream().sorted().toList());
        final var physicalBeforeMetadata = player.snapshotAabb();
        final boolean crawlingSizeBefore = FluidMovement.hasCrawlingSizeRequest(player);
        if (flags != null) {
            final boolean canClimb = player.getFlagTracker().has(19);

            final Set<Integer> clientVisibleFlags = EntityMetadataUtil.mergeFlags(
                    player.getFlagTracker().cloneFlags(), flags, hasBaseFlags, hasExtendedFlags);
            final boolean metadataSwimming = clientVisibleFlags.contains(57);
            final boolean metadataSprinting = clientVisibleFlags.contains(3);











            if (hasBaseFlags) {
                clientVisibleFlags.remove(FlagTracker.USING_ITEM);
                if (clientVisibleFlags.contains(4)) {
                    clientVisibleFlags.add(FlagTracker.USING_ITEM);
                }
            }
            player.getFlagTracker().set(player, clientVisibleFlags);
            player.getFlagTracker().set(19, canClimb);




            if (hasBaseFlags) {
                player.setServerSprinting(metadataSprinting);
                player.setClientSwimming(metadataSwimming);
                player.getFlagTracker().set(1,
                        clientVisibleFlags.contains(1));
            }



            if (hasBaseFlags) {
                syncGlidingFlag(player, clientVisibleFlags.contains(32));
            }
            FluidMovement.updatePoseForMetadata(player);

        }

        if (width != null) {




            updateWidth(player, width);
        }
        if (height != null) {


            updateHeight(player, height);
        }
        if (scale != null) {
            updateScale(player, scale);
        }
        if (FluidMovement.finishMetadataPose(player, physicalBeforeMetadata, crawlingSizeBefore, width, height, scale,
                flags != null && (hasBaseFlags || hasExtendedFlags))) {
            player.stage("POSE_METADATA_END","requestStaged",true);
            return;
        }
        if (height != null && Float.isFinite(height) && height > 0F
                || scale != null && Float.isFinite(scale) && scale > 0F) {
            FluidMovement.acknowledgeDimensions(player);
        } else if (width != null && Float.isFinite(width) && width > 0F) {
            FluidMovement.acknowledgeWidth(player);
        }
        player.stage("POSE_METADATA_END","requestStaged",false);
    }

    public static <I> boolean hasElytraEquipped(Context<I> player) {
        final I chest = player.compensatedChest();
        return chest != null && !player.isNull(chest) && player.nativeItemId(chest) == 444;
    }

    public static <I> void syncGlidingFlag(Context<I> player, final boolean requestedGliding) {
        player.getFlagTracker().set(32, requestedGliding && hasElytraEquipped(player));
    }

    private static void updateWidth(Context<?> player, final float width) {
        if (!Float.isFinite(width) || width <= 0F) {
            return;
        }

        player.applyDimensions(EntityDimensions.fixed(width, player.dimensions().height())
                .withEyeHeight(player.dimensions().eyeHeight()));
    }

    private static void updateHeight(Context<?> player, final float height) {
        if (!Float.isFinite(height) || height <= 0F) {
            return;
        }

        player.applyDimensions(EntityDimensions.fixed(player.dimensions().width(), height)
                .withEyeHeight(eyeHeightFor(height)));
    }

    private static void updateScale(Context<?> player, final float scale) {
        if (!Float.isFinite(scale) || scale <= 0F) {
            return;
        }

        player.applyDimensions(player.dimensions().hardScaled(scale));
    }

    private static float eyeHeightFor(final float height) {




        if (Math.abs(height - 0.2F) <= 1.0E-3F) {
            return 0.2F;
        }
        if (Math.abs(height - 0.625F) <= 1.0E-3F) {
            return 0.42F;
        }
        if (Math.abs(height - 0.6F) <= 1.0E-3F) {
            return 0.42F;
        }
        if (Math.abs(height - 1.49F) <= 1.0E-3F
                || Math.abs(height - 1.65F) <= 1.0E-3F) {
            return 1.26F;
        }
        if (Math.abs(height - 1.5F) <= 1.0E-3F) {
            return 1.27F;
        }
        return 1.62F;
    }

}
