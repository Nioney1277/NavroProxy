package ac.navro.proxy.util.reach;

import ac.navro.anticheat.util.Pair;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.anticheat.util.math.Box;
import java.util.ArrayDeque;





public final class CameraCandidates {
    private CameraCandidates() { }
    private static final double EPS = 1e-8;
    public static final int NODE_LIMIT = 512;
    public record Point(double x, double y, double z) { }
    public record Witness(double alpha, Point origin, Point targetPoint, double distance) { }
    public record Result(String verdict, String conditionalVerdict, String reason,
                         int nodes, Witness witness, double verticalFovDegrees,
                         double aspect, double rollDegrees, double maxDistance,
                         boolean coverageProven, boolean visibilityChecked) { }


    public static Result assess(PlacementCameraSnapshot camera, Pair<Vec3, Vec3> positions,
                                float eyeHeight, Box target, double maxDistance) {
        return solve(camera, positions, eyeHeight, target, maxDistance, 110, 16.0 / 9, NODE_LIMIT);
    }

    private static Result solve(PlacementCameraSnapshot camera, Pair<Vec3, Vec3> positions,
                                float eyeHeight, Box target, double maxDistance,
                                double fov, double aspect, int maxNodes) {
        if (camera == null || !finite(camera.forward()) || positions == null
                || !finite(positions.a()) || !finite(positions.b()) || !Float.isFinite(eyeHeight)
                || target == null || !target.isValid()
                || !Double.isFinite(fov) || fov <= 0 || fov >= 180
                || !Double.isFinite(aspect) || aspect <= 0
                || !Double.isFinite(maxDistance) || maxDistance <= 0) {
            return result("UNKNOWN", "invalid_or_missing_camera_geometry", 0, null, fov, aspect, maxDistance);
        }
        var b = target;
        double[] low = {0, b.minX - .1, b.minY - .1, b.minZ - .1};
        double[] high = {1, b.maxX + .1, b.maxY + .1, b.maxZ + .1};
        for (int i = 1; i < 4; i++) {
            if (!Double.isFinite(low[i]) || !Double.isFinite(high[i]) || high[i] - low[i] <= .2)
                return result("UNKNOWN", "invalid_target", 0, null, fov, aspect, maxDistance);
        }
        double[] f = vector(camera.forward());
        if (!normalize(f)) return result("UNKNOWN", "invalid_camera_vector", 0, null, fov, aspect, maxDistance);
        double[] right = Math.hypot(f[0], f[2]) < 1e-12 ? new double[]{1, 0, 0} : new double[]{f[2], 0, -f[0]};
        normalize(right);
        double[] up = {f[1]*right[2]-f[2]*right[1], f[2]*right[0]-f[0]*right[2], f[0]*right[1]-f[1]*right[0]};
        normalize(up);
        double tv = Math.tan(Math.toRadians(fov) / 2), th = tv * aspect;
        double[] start = vector(positions.a()), end = vector(positions.b());
        start[1] += eyeHeight; end[1] += eyeHeight;
        double[] delta = {end[0]-start[0], end[1]-start[1], end[2]-start[2]};
        double originTravel = Math.sqrt(dot(delta, delta));
        double[][] planes = new double[5][3];
        for (int i = 0; i < 3; i++) {
            planes[0][i] = -f[i];
            planes[1][i] = -right[i]-th*f[i]; planes[2][i] = right[i]-th*f[i];
            planes[3][i] = -up[i]-tv*f[i]; planes[4][i] = up[i]-tv*f[i];
        }
        var pending = new ArrayDeque<double[][]>();
        pending.push(new double[][]{low, high});
        int nodes = 0; boolean unresolved = false;
        while (!pending.isEmpty()) {
            if (nodes >= Math.min(NODE_LIMIT, Math.max(0, maxNodes)))
                return result("UNKNOWN", "node_budget", nodes, null, fov, aspect, maxDistance);
            double[][] domain = pending.pop(); low = domain[0]; high = domain[1]; nodes++;
            boolean outside = false;
            for (double[] n : planes) {
                double[] c = {-dot(n, delta), n[0], n[1], n[2]};
                if (lower(c, low, high, -dot(n, start)) > EPS) { outside = true; break; }
            }
            if (outside) continue;
            double distanceLower = 0;
            for (int i = 0; i < 3; i++) {
                double[] c = {-delta[i], 0, 0, 0}; c[i+1] = 1;
                double lo = lower(c, low, high, -start[i]);
                double hi = -lower(negate(c), low, high, start[i]);
                double gap = Math.max(0, Math.max(lo, -hi));
                distanceLower = Math.nextDown(distanceLower + Math.nextDown(gap*gap));
            }
            if (distanceLower > (maxDistance+EPS)*(maxDistance+EPS)) continue;
            double alpha = (low[0]+high[0])*.5;
            double[] origin = new double[3], point = new double[3], v = new double[3];
            for (int i = 0; i < 3; i++) {
                origin[i] = start[i]+alpha*delta[i]; point[i] = (low[i+1]+high[i+1])*.5;
                v[i] = point[i]-origin[i];
            }
            double depth = dot(v, f), distance = Math.sqrt(dot(v, v));
            if (depth >= -EPS && distance <= maxDistance-EPS
                    && Math.abs(dot(v, right)) <= th*depth-EPS
                    && Math.abs(dot(v, up)) <= tv*depth-EPS) {
                return result("POSSIBLE", "candidate_found", nodes,
                        new Witness(alpha, point(origin), point(point), distance), fov, aspect, maxDistance);
            }
            int axis = 0; double width = (high[0]-low[0])*originTravel;
            for (int i = 1; i < 4; i++) if (high[i]-low[i] > width) { axis = i; width = high[i]-low[i]; }
            double mid = (low[axis]+high[axis])*.5;
            if (width <= 1e-7 || mid == low[axis] || mid == high[axis]) { unresolved = true; continue; }
            double[] leftHigh = high.clone(), rightLow = low.clone();
            leftHigh[axis] = mid; rightLow[axis] = mid;
            pending.push(new double[][]{rightLow, high}); pending.push(new double[][]{low, leftHigh});
        }
        return result(unresolved ? "UNKNOWN" : "MISS", unresolved ? "unresolved_boundary" : "all_domains_pruned",
                nodes, null, fov, aspect, maxDistance);
    }

    private static Result result(String conditional, String reason, int nodes, Witness witness,
                                  double fov, double aspect, double distance) {
        return new Result("UNKNOWN", conditional, reason, nodes, witness, fov, aspect, 0, distance, false, false);
    }
    private static double lower(double[] c, double[] low, double[] high, double constant) {
        double sum = constant;
        for (int i = 0; i < 4; i++) sum = Math.nextDown(sum + Math.nextDown(Math.min(c[i]*low[i], c[i]*high[i])));
        return sum;
    }
    private static double[] negate(double[] v) { return new double[]{-v[0], -v[1], -v[2], -v[3]}; }
    private static boolean finite(Vec3 v) { return v != null && Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z); }
    private static double[] vector(Vec3 v) { return new double[]{v.x, v.y, v.z}; }
    private static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static boolean normalize(double[] v) {
        double length = Math.sqrt(dot(v, v));
        if (!Double.isFinite(length) || length < 1e-10) return false;
        for (int i = 0; i < 3; i++) v[i] /= length;
        return true;
    }
    private static Point point(double[] v) { return new Point(v[0], v[1], v[2]); }
}
