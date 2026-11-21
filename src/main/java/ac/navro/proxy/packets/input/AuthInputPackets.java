package ac.navro.proxy.packets.input;
import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.live.PredictionItemUse;
import ac.navro.proxy.packets.player.PlayerVelocityPackets;
import ac.navro.proxy.packets.input.legacy.LegacyAuthInputPackets;
import ac.navro.proxy.validator.blockbreak.ServerBreakBlockValidator;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;

import ac.navro.proxy.data.input.PredictionData;


import ac.navro.proxy.collision.Collider;
import ac.navro.proxy.prediction.PredictionRunner;
import ac.navro.proxy.prediction.engine.data.Vector;
import ac.navro.proxy.prediction.fluid.FluidMovement;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.check.api.impl.OffsetHandlerCheck;


import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.check.impl.prediction.Prediction;
import ac.navro.proxy.collision.ProtocolEntityTolerance;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;


public final class AuthInputPackets {
    private final SessionState session;
    private final PredictionContext player;
    private final PredictionItemUse itemUse;
    private Result lastResult;
    private final PlayerVelocityPackets velocityPackets;
    private final ServerBreakBlockValidator blockBreakValidator;

    public AuthInputPackets(SessionState session) {
        this.session=session;
        this.player=new PredictionContext(session);
        this.velocityPackets=new PlayerVelocityPackets(session, player);
        this.blockBreakValidator=new ServerBreakBlockValidator(new ServerBreakBlockValidator.Context() {
            public CompensatedWorld world(){return session.world();}
            public Vec3 position(){return session.player().position();}
            public ac.navro.proxy.data.vanilla.GameType gameType(){return session.player().gameType();}
            public String name(){return session.player().getName();}
            public void restoreCorrectBlock(org.cloudburstmc.math.vector.Vector3i position){session.restoreCorrectBlock(position);}
        });
        this.itemUse=new PredictionItemUse(session);
        this.player.phaseObserver=(sample,reason,offset,entry)->{var trace=session.player().worldDiagnostics();if(trace!=null)trace.phase(sample,reason,offset,entry);};
        session.player().setPredictionInputHandler(input -> LegacyAuthInputPackets.processAuthInput(player, itemUse, input));
        session.player().setMovementMetadataContext(new ac.navro.proxy.state.MovementMetadataAdapter(session, itemUse));
        session.player().setPredictionRewind(session::rewindPrediction);
        session.player().getCheckHolder().registerPredictionHandlers(player);
    }
    public PredictionContext context(){return player;}
    public Result lastResult(){return lastResult;}

    public PredictionItemUse itemUse(){return itemUse;}
    public void reset(){
        player.uncertainVelocity=player.certainVelocity=null;
        player.stuckSpeedMultiplier=Vec3.ZERO.clone();player.lastTickFinalVelocity=session.player().velocity().clone();
        player.climbJumpDelay=player.scaffoldingSneakHoldTicks=player.ticksSinceCanSlowdown=0;
        player.bounceGravityCorrection=null;
        player.stopRiptide();
        player.beforeCollision=player.afterCollision=Vec3.ZERO.clone();
        player.velocity=session.player().velocity().clone();
        player.position=session.player().position().clone();player.boundingBox=session.player().boundingBox().clone();player.dimensions=session.player().dimensions();
        player.predictionStartBoundingBox=player.boundingBox.clone();
        player.predictionResult=new PredictionData(Vec3.ZERO.clone(),Vec3.ZERO.clone(),player.velocity.clone());
        player.bestPossibility=new Vector(ac.navro.proxy.prediction.engine.data.VectorType.NORMAL,Vec3.ZERO.clone());
        lastResult=null;
    }

    public void run(PlayerAuthInputPacket input){
        if (session.player().awaitingRespawnHealth() || session.awaitingBackendSpawnPosition()) return;
        player.begin();
        if(session.teleports().isTeleporting() || session.teleports().hasPendingBackendRewind() || !session.spawned())return;
        if(player.vehicleData!=null){
            session.player().setResolvedPosition(player.unvalidatedPosition,player.boundingBox,false,true);
            return;
        }
        if(session.player().predictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.SLEEPING))return;
        if(session.player().isMovementExempted()){
            session.teleports().finishCorrectionCooldown();
            processExemptedMovement(session.player());
        }else if((!session.player().inLoadingScreen && session.player().sinceLoadingScreen>=2)
                || player.unvalidatedTickEnd.lengthSquared()>0){
            new PredictionRunner(player).run();
            player.syncWire();
        }else{
            player.velocity=Vec3.ZERO.clone();
            player.syncWire();
        }
        finishPrediction(input);
    }

    public void processExemptedMovement(){
        var wire=session.player();
        if (session.teleports().isTeleporting() || wire.awaitingRespawnHealth() || session.awaitingBackendSpawnPosition() || !session.spawned()
                || wire.vehicleData!=null || wire.predictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.SLEEPING)) return;
        player.begin();
        processExemptedMovement(wire);
    }

    private void processExemptedMovement(WirePlayerState wire){
        if(!wire.inLoadingScreen && player.boundingBox.isValid() && !player.noClip()
                && wire.gameType()!=GameType.SPECTATOR && !wire.flying() && !wire.wasFlying()){
            new PredictionRunner(player).run();
            Vec3 target=wire.inputPositionTarget();
            if(player.inputPositionDistance(player.position)<player.maxOffset)
                Collider.acceptInputPosition(wire,session.collisionWorld(),target);
            else wire.rebasePosition(target,false);
            player.lastTickFinalVelocity=player.unvalidatedTickEnd.clone();
            player.predictionResult=new PredictionData(player.beforeCollision.clone(),player.afterCollision.clone(),
                    player.unvalidatedTickEnd.clone());
        }else{
            wire.setCollisionPenetratingFrames(0);
            player.collisionPhysics.reset();
            player.fallDistance=0;
            FluidMovement.finishInput(player);
            session.fluidState().resetMovement();
            player.fluidPrediction.load(session.fluidState());
            player.fluidPrediction.inputActive=false;
            player.fluidPrediction.swimSpeedMultiplier=1F;
            player.fluidPrediction.waterDrag=0.8F;
            player.bounceGravityCorrection=null;
            player.movementCollisionEntries=java.util.List.of();
            wire.setMovementCollisionEntries(java.util.List.of());
            wire.acceptPosition(player.unvalidatedPosition.clone());
            player.inBlockState=null;
            Vec3 after=player.velocity.y<0 && player.hasInput(PlayerAuthInputData.VERTICAL_COLLISION)
                    ? new Vec3(0,1,0) : Vec3.ZERO.clone();
            player.predictionResult=new PredictionData(Vec3.ZERO.clone(),after,player.unvalidatedTickEnd.clone());
        }
        player.position=wire.position().clone();
        player.boundingBox=wire.boundingBox().clone();
        player.certainVelocity=null;
        player.velocity=player.unvalidatedTickEnd.clone();
        wire.setVelocity(player.velocity);
        player.bestPossibility=Vector.NONE;
    }


    public void finishTeleportConfirmation(PlayerAuthInputPacket input) {
        player.begin();
        finishPrediction(input);
    }

    private void finishPrediction(PlayerAuthInputPacket input) {
        if(!session.spawned()) return;
        player.insideUnloadedChunk=!player.compensatedWorld.isChunkLoadedAt(player.position.x,player.position.z);
        if(player.insideUnloadedChunk){
            if(session.player().worldDiagnostics()!=null)session.player().worldDiagnostics().terrainWait(player);
            session.teleports().teleport(session.teleports().getLastKnowValid());
        }
        if(session.teleports().isTeleporting() || session.player().inLoadingScreen)return;

        session.cachePredictionFrame(player);
        float raw=player.inputPositionDistance(player.position),offset=raw;
        player.entityTolerance=session.sampleEntityTolerance().tolerance();
        if(player.entityTolerance.collided() && offset>=player.maxOffset)
            offset=ProtocolEntityTolerance.adjustedMotionOffset(player.lastTickFinalVelocity,player.unvalidatedTickEnd,player.entityTolerance);
        if(session.player().worldDiagnostics()!=null)session.player().worldDiagnostics().frame(player,offset);
        if(session.player().isMovementExempted()){
            session.player().completePredictionInput();
            lastResult=new Result(player.tick,raw,offset,player.position.clone(),player.lastTickFinalVelocity.clone(),true);
            return;
        }
        var elytra=(ac.navro.proxy.check.impl.elytra.ElytraA)session.player().getCheckHolder().get(
                ac.navro.proxy.check.impl.elytra.ElytraA.class);
        if(elytra!=null&&session.player().checkEnabled(elytra.getClass())&&elytra.onPredictionComplete(input)){
            LegacyAuthInputPackets.correctInputData(session.player(),input);
            session.player().completePredictionInput();
            lastResult=new Result(player.tick,raw,offset,player.position.clone(),player.lastTickFinalVelocity.clone(),false);
            return;
        }
        boolean checked=!player.insideUnloadedChunk && player.vehicleData==null
                && session.player().gameType()!=GameType.SPECTATOR && !session.teleports().isTeleporting();
        boolean inCorrection = session.teleports().hasPendingCorrection() || session.teleports().isCorrectionCooldown();
        for(var check:session.player().getCheckHolder().values()){
            if (inCorrection && check instanceof Prediction) continue;
            if(check instanceof OffsetHandlerCheck handler && session.player().checkEnabled(check.getClass()))
                handler.onPredictionComplete(offset,checked);
        }
        boolean blockPush=player.collisionPhysics.pushedByBlock();
        boolean canAcceptClientMovement=session.teleports().canAcceptClientMovement();
        if(player.entityTolerance.collided()){
            if(canAcceptClientMovement&&!blockPush){
                Vec3 position=ProtocolEntityTolerance.clampAccepted(player.position,player.unvalidatedPosition,player.entityTolerance);
                Vec3 motion=ProtocolEntityTolerance.clampAccepted(player.lastTickFinalVelocity,player.unvalidatedTickEnd,player.entityTolerance);
                session.player().rebasePosition(position,false);session.player().setVelocity(motion);
                player.collisionPhysics.acceptClientPosition();player.velocity=motion.clone();player.lastTickFinalVelocity=motion.clone();
                session.acceptValidatedMovementBaseline(session.player().snapshotAabb(),session.playerYOffset());
            }
        }else if(canAcceptClientMovement && player.velocity.distanceTo(player.unvalidatedTickEnd)<player.maxOffset){
            player.velocity=player.unvalidatedTickEnd.clone();session.player().setVelocity(player.velocity);
        }
        LegacyAuthInputPackets.correctInputData(session.player(), input);
        if(!player.entityTolerance.collided() && offset<player.maxOffset && canAcceptClientMovement)
            ac.navro.proxy.collision.Collider.acceptInputPosition(session.player(),session.collisionWorld(),session.player().inputPositionTarget());
        session.teleports().finishCorrectionCooldown();
        session.player().completePredictionInput();
        lastResult=new Result(player.tick,raw,offset,player.position.clone(),player.lastTickFinalVelocity.clone(),!session.teleports().isTeleporting());
    }
    public record Result(long tick,float rawOffset,float checkedOffset,Vec3 predictedPosition,Vec3 predictedVelocity,boolean accepted){}
    public PlayerVelocityPackets velocityPackets(){return velocityPackets;}


    public void beforeInput(PlayerAuthInputPacket input){
        boolean digging=input.getInputData().contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS);
        long revision=session.world().bucketPredictionRevision();
        blockBreakValidator.handle(input);
        var log=session.player().worldDiagnostics();
        if(digging && log!=null)log.event("block_break_validated",
                ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                        "inputTick",input.getTick(),"actions",input.getPlayerActions().toString(),
                        "revisionBefore",revision,"revisionAfter",session.world().bucketPredictionRevision()));
        var report = session.player().predictionSync().consume();
        if (report != null && log != null) log.predictionSyncAtInput(input.getTick(), report);
    }

    public static void onPacketSend(WirePlayerState player, CompensatedWorld world,
                                     ChangeDimensionPacket packet, Runnable acknowledgedReset) {
        final int dimension = packet.getDimension();
        final Integer loadingScreenId = packet.getLoadingScreenId();
        player.queueAcknowledgment(packet, () -> {
            if (dimension != world.dimension()) {
                player.currentLoadingScreen = loadingScreenId;
                player.inLoadingScreen = true;
            }

            acknowledgedReset.run();
        });
    }
}
