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


import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.check.impl.prediction.Prediction;
import ac.navro.proxy.collision.ProtocolEntityTolerance;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;


public final class AuthInputPackets {
    private final SessionState session;
    private final PredictionContext player;
    private final Prediction prediction;
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
        this.prediction=new Prediction(session.player(),player);
        session.player().setPredictionRewind(session::rewindPrediction);
        session.player().getCheckHolder().put(Prediction.class,prediction);
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
        if(session.teleports().isTeleporting() || !session.spawned())return;
        if(player.vehicleData!=null){
            session.player().setResolvedPosition(player.unvalidatedPosition,player.boundingBox,false,true);
            return;
        }
        if(session.player().predictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.SLEEPING))return;
        boolean ordinary=player.boundingBox.isValid() && !player.noClip()
                && session.player().gameType()!=ac.navro.proxy.data.vanilla.GameType.SPECTATOR
                && !session.player().flying() && !session.player().wasFlying();
        if(ordinary && ((!session.player().inLoadingScreen && session.player().sinceLoadingScreen>=2)
                || player.unvalidatedTickEnd.lengthSquared()>0)){
            new PredictionRunner(player).run();
        }else if(!session.player().isMovementExempted()){
            player.velocity=Vec3.ZERO.clone();
        }
        if(session.player().isMovementExempted()){
            Vec3 target=session.player().inputPositionTarget();
            if(player.inputPositionDistance(player.position)<player.maxOffset)
                ac.navro.proxy.collision.Collider.acceptInputPosition(session.player(),session.collisionWorld(),target);
            else session.player().rebasePosition(target,false);
            session.player().setVelocity(player.unvalidatedTickEnd);
            player.lastTickFinalVelocity=player.unvalidatedTickEnd.clone();
            player.certainVelocity=null;
            session.player().completePredictionInput();
            return;
        }
        player.syncWire();
        finishPrediction(input);
    }


    public void finishTeleportConfirmation(PlayerAuthInputPacket input) {
        player.begin();
        finishPrediction(input);
    }

    private void finishPrediction(PlayerAuthInputPacket input) {
        if(session.teleports().isTeleporting() || !session.spawned()) return;
        if(session.player().inLoadingScreen)return;
        player.insideUnloadedChunk=!player.compensatedWorld.isChunkLoadedAt(player.position.x,player.position.z);
        if(player.insideUnloadedChunk){
            if(session.player().worldDiagnostics()!=null)session.player().worldDiagnostics().terrainWait(player);
            session.teleports().teleport(session.teleports().getLastKnowValid());
            return;
        }

        session.cachePredictionFrame(player);
        float raw=player.inputPositionDistance(player.position),offset=raw;
        player.entityTolerance=session.sampleEntityTolerance().tolerance();
        if(player.entityTolerance.collided() && offset>=player.maxOffset)
            offset=ProtocolEntityTolerance.adjustedMotionOffset(player.lastTickFinalVelocity,player.unvalidatedTickEnd,player.entityTolerance);
        if(session.player().worldDiagnostics()!=null)session.player().worldDiagnostics().frame(player,offset);
        var elytra=(ac.navro.proxy.check.impl.elytra.ElytraA)session.player().getCheckHolder().get(
                ac.navro.proxy.check.impl.elytra.ElytraA.class);
        if(elytra!=null&&session.player().checkEnabled(elytra.getClass())&&elytra.onPredictionComplete(input)){
            LegacyAuthInputPackets.correctInputData(session.player(),input);
            session.player().completePredictionInput();
            lastResult=new Result(player.tick,raw,offset,player.position.clone(),player.lastTickFinalVelocity.clone(),false);
            return;
        }
        if(session.player().checkEnabled(prediction.getClass()))prediction.onPredictionComplete(offset,true);
        if(!player.disabledChecks.contains("DebugOffset"))
            ac.navro.proxy.check.impl.prediction.DebugOffsetA.onPredictionComplete(session,player,offset);
        boolean blockPush=player.collisionPhysics.pushedByBlock();
        if(player.entityTolerance.collided()){
            if(!session.teleports().isTeleporting()&&!blockPush){
                Vec3 position=ProtocolEntityTolerance.clampAccepted(player.position,player.unvalidatedPosition,player.entityTolerance);
                Vec3 motion=ProtocolEntityTolerance.clampAccepted(player.lastTickFinalVelocity,player.unvalidatedTickEnd,player.entityTolerance);
                session.player().rebasePosition(position,false);session.player().setVelocity(motion);
                player.collisionPhysics.acceptClientPosition();player.velocity=motion.clone();player.lastTickFinalVelocity=motion.clone();
                session.acceptValidatedMovementBaseline(session.player().snapshotAabb(),session.playerYOffset());
            }
        }else if(player.velocity.distanceTo(player.unvalidatedTickEnd)<player.maxOffset){
            player.velocity=player.unvalidatedTickEnd.clone();session.player().setVelocity(player.velocity);
        }
        LegacyAuthInputPackets.correctInputData(session.player(), input);
        if(!player.entityTolerance.collided() && offset<player.maxOffset && !session.teleports().isTeleporting())
            ac.navro.proxy.collision.Collider.acceptInputPosition(session.player(),session.collisionWorld(),session.player().inputPositionTarget());
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
