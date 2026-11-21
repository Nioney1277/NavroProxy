"""Compile the exact original post listener and TickData with explicit session/lifecycle test doubles.
No native server is started. Fluid/collision/item-use simulation is outside this fixture; these
boundaries only supply snapshots. Native packet/vector classes and correctInputData are executed.
"""
from pathlib import Path
import subprocess,shutil,json,hashlib
root=Path(__file__).resolve().parent.parent
original=root.parent/'Navro';out=root/'build/post-input-native';sources=out/'src';classes=out/'classes';sources.mkdir(parents=True,exist_ok=True);classes.mkdir(exist_ok=True)
def write(name,text):
 p=sources/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
for rel in ['packets/input/PostAuthInputPackets.java','data/input/TickData.java']:
 src=original/'src/main/java/ac/navro/anticheat'/rel;write('ac/navro/anticheat/'+rel,src.read_text())
src=original/'src/main/java/ac/navro/anticheat/packets/input/legacy/LegacyAuthInputPackets.java';text=src.read_text();a=text.index('    public static void correctInputData(');brace=text.index('{',a);depth=1;i=brace+1
while depth:
 depth+=(text[i]=='{')-(text[i]=='}');i+=1
write('ac/navro/anticheat/packets/input/legacy/LegacyAuthInputPackets.java','package ac.navro.anticheat.packets.input.legacy;\nimport ac.navro.anticheat.player.NavroPlayer;import cn.nukkit.network.protocol.PlayerAuthInputPacket;import cn.nukkit.network.protocol.types.AuthInputAction;\npublic final class LegacyAuthInputPackets{\n'+text[a:i]+'\n}\n')
write('cn/nukkit/Player.java','package cn.nukkit; public class Player {public boolean spawned; public Object riding;}')
write('ac/navro/anticheat/Navro.java','''package ac.navro.anticheat;
import ac.navro.anticheat.player.NavroPlayer;
public class Navro {public static NavroPlayer player; public static Navro getInstance(){return new Navro();}public Manager getPlayerManager(){return new Manager();} public static class Manager{public NavroPlayer get(cn.nukkit.Player ignored){return player;}}}
''')
write('ac/navro/anticheat/data/ItemUseTracker.java','''package ac.navro.anticheat.data;
public class ItemUseTracker{public static class Snapshot{}public java.util.Set<Integer> snapshotPredictionFlags(long tick){return java.util.Set.of(4);}public Snapshot snapshot(){return new Snapshot();}}
''')
write('ac/navro/anticheat/prediction/RiptidePhysics.java','''package ac.navro.anticheat.prediction;
public class RiptidePhysics{public static class Snapshot{}public static Snapshot snapshot(ac.navro.anticheat.player.NavroPlayer player){return new Snapshot();}}
''')
write('ac/navro/anticheat/collision/CollisionPhysicsState.java','''package ac.navro.anticheat.collision;
public class CollisionPhysicsState{public static class Snapshot{}public Snapshot snapshot(){return new Snapshot();}}
''')
write('ac/navro/anticheat/prediction/fluid/FluidMovement.java','''package ac.navro.anticheat.prediction.fluid;
public class FluidMovement {public static void finishInput(ac.navro.anticheat.player.NavroPlayer player){player.finishedFluid++;}}
''')
write('ac/navro/anticheat/player/NavroPlayer.java','''package ac.navro.anticheat.player;
import java.util.*;
import ac.navro.anticheat.data.*;
import ac.navro.anticheat.data.input.*;
import ac.navro.anticheat.data.vanilla.AttributeInstance;
import ac.navro.anticheat.prediction.RiptidePhysics;
public class NavroPlayer {
 public boolean exempt,movementExempt;public cn.nukkit.Player session=new cn.nukkit.Player();
 public int tickSinceBlockResync,finishedFluid;public long tick=80;public float offset;
 public boolean thisTickSpinAttack=true,thisTickOnGroundSpinAttack=true,doingInventoryAction=true,hasDepthStrider=true;
 public int thisTickRiptideLevel=3,collisionPenetratingFrames;public float fallDistance;
 public RiptidePhysics.Snapshot riptideTickStartState=new RiptidePhysics.Snapshot();
 public Object vehicleData;public EntityDimensions dimensions;public InputPosition inputPosition;
 public MovementAabbSnapshot inputAabbStart;
 public ac.navro.anticheat.util.math.Vec3 position,velocity;
 public boolean horizontalCollision,verticalCollision;
 public ac.navro.anticheat.collision.CollisionPhysicsState collisionPhysics=new ac.navro.anticheat.collision.CollisionPhysicsState();
 public Fluid fluidPrediction=new Fluid();
 public Sampler fenceSampler=new Sampler(),chestSampler=new Sampler(),lavaSampler=new Sampler(),ceilingSampler=new Sampler(),collisionSampler=new Sampler(),trapdoorSampler=new Sampler(),movementSampler=new Sampler(),swimDebug=new Sampler(),itemUseDebug=new Sampler();
 public Teleports teleports=new Teleports();public boolean isExempted(){return exempt;}public boolean isMovementExempted(){return movementExempt;}
 public cn.nukkit.Player getSession(){return session;}public float getYOffset(){return offset;}public Teleports getTeleportUtil(){return teleports;}
 public MovementAabbSnapshot snapshotAabb(){return null;}public ItemUseTracker getItemUseTracker(){return new ItemUseTracker();}
 public Map<String,AttributeInstance> cloneAttributes(){return Map.of();}
 public boolean isClientSprinting(){return false;}public boolean isServerSprinting(){return false;}public boolean isPredictionSprinting(){return false;}public boolean isClientSwimming(){return false;}public boolean isPredictionSwimming(){return false;}
 public static class Sampler{public void finish(cn.nukkit.network.protocol.PlayerAuthInputPacket packet,boolean cancelled){}public void stage(String n,Object...v){}}
 public static class Fluid{public ac.navro.anticheat.prediction.fluid.FluidPredictionState.Snapshot snapshot(){return null;}}
 public static class Teleports{public int mode,polls;public Map<Long,TickData> history=new TreeMap<>();public Map<Long,TickData> getAuthInputHistory(){return history;}public boolean isHardTeleporting(){return mode==2;}public boolean isTeleporting(){return mode!=0;}public void pollRewindHistory(){polls++;}}
}
''')
cp=str(original/'build/classes/java/main')+':'+str(original/'Nukkit-MOT-SNAPSHOT.jar')
cmd=['javac','-cp',cp,'-sourcepath',str(sources),'-d',str(classes)]+[str(p) for p in sources.rglob('*.java')]+[str(root/'tools/NavroPostInputFixture.java')]
with (root/'build/post-input-native-compile.log').open('w') as log:subprocess.run(cmd,cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
with (root/'build/post-input-native.log').open('w') as log:subprocess.run(['java','-XX:ActiveProcessorCount=4','-Xmx256M','-cp',str(classes)+':'+cp,'NavroPostInputFixture',str(root/'src/test/resources/navro-post-input.bin.gz')],cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
print((root/'build/post-input-native.log').read_text())
