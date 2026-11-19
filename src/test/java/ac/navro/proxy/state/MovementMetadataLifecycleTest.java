package ac.navro.proxy.state;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.proxy.prediction.fluid.FluidMovement;
import ac.navro.proxy.data.ItemUseTracker;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class MovementMetadataLifecycleTest {
    static MovementMetadataTestModel model()throws Exception{
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(MovementMetadataLifecycleTest.class.getResourceAsStream("/navro-movement-metadata.jsonl.gz")),StandardCharsets.UTF_8))){return new MovementMetadataTestModel(JsonParser.parseString(in.readLine()).getAsJsonObject(),0);}
    }
    @Test void baseWordAppliesUseHandshakeAndPhysicalFlagsWithoutChangingInputSprint()throws Exception{
        var m=model();m.tracker.use(m.item(0),261,false);m.trackedFlags.set(19,true);m.trackedFlags.set(1,false);m.trackedFlags.set(57,false);m.chest=m.item(5);
        MovementMetadata.applyClientVisibleMovementMetadata(m,null,null,null,Set.of(1,3,4,32,57),true,false);
        assertTrue(m.hasFlag(19));assertTrue(m.hasFlag(1));assertTrue(m.hasFlag(57));assertTrue(m.hasFlag(-1));assertTrue(m.hasFlag(32));assertTrue(m.serverSprinting);assertFalse(m.clientSprinting);assertTrue(m.clientSwimming);assertEquals(ItemUseTracker.DirtyUsing.NONE,m.tracker.getDirtyUsing());assertEquals(1.8F,m.dimensions().height());assertEquals(0.42F,m.dimensions().eyeHeight());
        assertTrue(m.events.indexOf("serverSprinting")<m.events.indexOf("clientSwimming"));assertTrue(m.events.indexOf("clientSwimming")<m.events.indexOf("compensatedChest"));assertTrue(m.events.indexOf("compensatedChest")<m.events.indexOf("POSE_UPDATE_BEGIN"));
    }
    @Test void extendedWordLeavesBaseStateAndGlidingGateUntouched()throws Exception{
        var m=model();m.trackedFlags.set(32,true);m.trackedFlags.set(1,true);m.trackedFlags.set(19,true);m.serverSprinting=true;
        MovementMetadata.applyClientVisibleMovementMetadata(m,null,null,null,Set.of(114,128,-9),false,true);
        assertTrue(m.hasFlag(32));assertTrue(m.hasFlag(1));assertTrue(m.hasFlag(19));assertTrue(m.hasFlag(114));assertFalse(m.hasFlag(128));assertFalse(m.hasFlag(-9));assertTrue(m.serverSprinting);assertFalse(m.clientSwimming);assertFalse(m.events.contains("serverSprinting"));assertFalse(m.events.contains("clientSwimming"));assertFalse(m.events.contains("compensatedChest"));
    }
    @Test void crawlRequestKeepsPhysicalHeightAndWidthAckUntilOwnedInputConsumesIt()throws Exception{
        var m=model();MovementMetadata.applyClientVisibleMovementMetadata(m,null,null,null,Set.of(114),false,true);
        assertEquals(1.8F,m.dimensions().height());assertEquals(0.6F,m.pose.pendingPoseDimensions.height());assertNull(m.pose.readyPoseDimensions);
        MovementMetadata.applyClientVisibleMovementMetadata(m,0.58F,null,null,null,false,false);
        assertEquals(0.58F,m.dimensions().width());assertEquals(1.8F,m.dimensions().height());assertEquals(0.58F,m.pose.pendingPoseDimensions.width());
        FluidMovement.beginInput(m);assertNull(m.pose.pendingPoseDimensions);assertEquals(0.6F,m.pose.readyPoseDimensions.height());assertFalse(m.pose.readyPoseBeforeMovement);
        m.trackedFlags.set(114,false);FluidMovement.updatePose(m);assertEquals(1.8F,m.pose.readyPoseDimensions.height());assertEquals(1.8F,m.dimensions().height());
        FluidMovement.finishInput(m);assertNull(m.pose.readyPoseDimensions);assertFalse(m.pose.inputActive);assertEquals(1.8F,m.dimensions().height());
    }
    @Test void standingFlagAckOwnsRequestAndNewHeightRetainsExactNativeDimensions()throws Exception{
        var m=model();m.applyDimensions(m.dimensions());var before=m.geometry.snapshotAabb();
        MovementMetadata.applyClientVisibleMovementMetadata(m,0.58F,1.49F,null,Set.of(),true,true);
        assertEquals(0.58F,m.dimensions().width());assertEquals(before.dimensions().height(),m.dimensions().height());assertEquals(before.box().minY,m.boundingBox().minY);assertEquals(1.49F,m.pose.readyPoseDimensions.height());assertEquals(1.26F,m.pose.readyPoseDimensions.eyeHeight());assertTrue(m.pose.readyPoseBeforeMovement);
        FluidMovement.beginInput(m);FluidMovement.updatePose(m);assertEquals(1.49F,m.dimensions().height());assertEquals(0.58F,m.dimensions().width());assertNull(m.pose.readyPoseDimensions);
        MovementMetadata.applyClientVisibleMovementMetadata(m,null,0.625F,1.001F,null,false,false);
        assertEquals(0.625F*1.001F,m.dimensions().height());assertEquals(0.42F,m.dimensions().eyeHeight());assertFalse(m.dimensions().fixed());assertNull(m.pose.pendingPoseDimensions);assertNull(m.pose.readyPoseDimensions);
    }
    @Test void elytraGateUsesNativeIdAndCountAndOnlyReadsChestForRequestedGlide()throws Exception{
        var m=model();m.failAt="compensatedChest";MovementMetadata.syncGlidingFlag(m,false);assertFalse(m.hasFlag(32));assertFalse(m.events.contains("compensatedChest"));m.failAt=null;
        m.chest=m.item(6);assertTrue(MovementMetadata.hasElytraEquipped(m));MovementMetadata.syncGlidingFlag(m,true);assertTrue(m.hasFlag(32));m.chest=m.cloneItem(m.chest);m.chest.count=0;MovementMetadata.syncGlidingFlag(m,true);assertFalse(m.hasFlag(32));m.chest=null;assertFalse(MovementMetadata.hasElytraEquipped(m));
    }
    @Test void failingChestReadRetainsEarlierFlagChangesAndDoesNotApplyDimensions()throws Exception{
        var m=model();m.trackedFlags.set(19,true);m.failAt="compensatedChest";var before=m.dimensions();
        assertThrows(IllegalStateException.class,()->MovementMetadata.applyClientVisibleMovementMetadata(m,0.58F,1.49F,2F,Set.of(1,3,32),true,false));
        assertTrue(m.serverSprinting);assertTrue(m.hasFlag(1));assertTrue(m.hasFlag(19));assertTrue(m.hasFlag(32));assertEquals(before,m.dimensions());assertFalse(m.events.contains("POSE_UPDATE_BEGIN"));assertFalse(m.events.contains("POSE_METADATA_END"));
    }
    @Test void finishInputClearsOwnershipBeforeSamplerAndKeepsOriginalSamplerOrderOnFailure()throws Exception{
        var m=model();m.pose.inputActive=true;m.pose.readyPoseDimensions=EntityDimensions.fixed(0.58F,0.625F).withEyeHeight(0.42F);m.pose.readyPoseBeforeMovement=true;m.failAt="movementSampler";
        assertThrows(IllegalStateException.class,()->FluidMovement.finishInput(m));assertFalse(m.pose.inputActive);assertFalse(m.pose.readyPoseBeforeMovement);assertNull(m.pose.readyPoseDimensions);assertEquals(0.625F,m.dimensions().height());assertFalse(m.events.contains("trapdoorSampler"));assertFalse(m.events.contains("POSE_FINISH_END"));
        m.failAt=null;m.events.clear();m.pose.inputActive=true;m.pose.readyPoseDimensions=m.dimensions();FluidMovement.finishInput(m);assertTrue(m.events.indexOf("movementSampler")<m.events.indexOf("trapdoorSampler"));
    }
    @Test void invalidDimensionValuesLeavePendingRequestsAndDiagnosticArgumentsIntact()throws Exception{
        var m=model();m.pose.pendingPoseDimensions=EntityDimensions.fixed(0.58F,0.625F).withEyeHeight(0.42F);m.pose.horizontalPoseApplied=true;var before=m.geometry.snapshotAabb();
        MovementMetadata.applyClientVisibleMovementMetadata(m,Float.NaN,Float.POSITIVE_INFINITY,-0F,null,false,false);
        assertEquals(MovementMetadataTestModel.snapshot(before),MovementMetadataTestModel.snapshot(m.geometry.snapshotAabb()));assertNotNull(m.pose.pendingPoseDimensions);assertFalse(m.events.contains("applyDimensions"));assertEquals(List.of("POSE_METADATA_BEGIN","snapshotAabb","POSE_METADATA_END"),m.events);assertEquals("POSE_METADATA_END[requestStaged, false]",m.debug.get(m.debug.size()-1));
    }
}
