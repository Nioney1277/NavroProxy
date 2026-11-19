package ac.navro.proxy.collision;

import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.anticheat.util.math.Box;


public record CollisionShapeEntry(Box shape, NetworkBlockState block) { }
