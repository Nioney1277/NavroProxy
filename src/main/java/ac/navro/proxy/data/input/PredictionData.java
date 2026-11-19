package ac.navro.proxy.data.input;

import ac.navro.anticheat.util.math.Vec3;

public record PredictionData(Vec3 before, Vec3 after, Vec3 tickEnd) { }
