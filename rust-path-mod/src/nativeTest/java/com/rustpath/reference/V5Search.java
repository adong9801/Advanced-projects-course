package com.rustpath.reference;




import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.rustpath.reference.V5Flags.*;

/**
 * V5's walking A* (V5Loader NativeSrc: pathfinder.cpp, pathfinder_runtime_common/walk.inl), in Java.
 * <ul>
 *   <li>Nodes are where the feet are: solid below, room for the body. Moves: the 4 sides and 4 diagonals (the corners
 *   clear at feet and head), a step up of one block, and a walk off an edge down to 20 blocks.</li>
 *   <li>Costs in ticks: sprinting a block (1 / 0.2806), a jump up 28 + 6 + a block's sprint (a slab or stair rise
 *   1.1 blocks' sprint), a fall its fall time + 2 (and the square of anything past 3).</li>
 *   <li>Every node also costs for how near an edge (a drop of 3) and a wall it is, looking 6 blocks out in the ways
 *   that aren't steps: 24 at an edge down to 0.5 six off, 17 at a wall down to 0.2. So paths keep to the middle of a
 *   tunnel and away from drops. +20 for fluid at the feet, and again at the head.</li>
 *   <li>Several starts (the second and later cost 250 more), several goals; heuristic × 1.05; the moves' order
 *   rotated by a seed for another path on a recalculation; zones to avoid (where it got stuck).</li>
 * </ul>
 */
public final class V5Search {
    private V5Search() {}

    static final float INF_COST = 1e6f;
    static final float SPRINT_ONE_BLOCK_TIME = 1.0f / 0.2806f;
    static final float SPRINT_DIAGONAL_TIME = SPRINT_ONE_BLOCK_TIME * 1.41421356f;
    /** A corner squeezed across between two thin things (panes): a little more than walked across in the open. */
    static final float SQUEEZE_PENALTY = 3f;
    static final float MOMENTUM_LOSS_PENALTY = 6.0f;
    static final float SLAB_ASCENT_TIME = SPRINT_ONE_BLOCK_TIME * 1.1f;
    static final float WALK_OFF_EDGE_TIME = SPRINT_ONE_BLOCK_TIME * 0.5f;
    static final float LAND_RECOVERY_TIME = 2.0f;
    static final float JUMP_UP_ONE_BLOCK_TIME = 28.0f + MOMENTUM_LOSS_PENALTY + SPRINT_ONE_BLOCK_TIME;
    /** A full jump up a step with a block right over it (or over where it's jumped from): taken only when there's no roomier way. */
    static final float LOW_STEP_PENALTY = 25.0f;
    /** A drop of 2 or more down into a pit: about 11 blocks sprinted, so a way round on the level that much longer is taken. */
    static final float PIT_PENALTY = 40.0f; // (about 7 blocks sprinted: a roomier way near by is taken, not a long way round)
    /**
     * A drop of 2 or more (walked off an edge, fallen down): dearer than V5 has it, by this and so much a block more — the
     * user: "prefer not dropping", "it still chooses routes that drop when a route that doesn't drop is so nearby" (off
     * JADE_1's #1 it went up and off a 6-block edge, 4 nodes shorter than down the stairs beside it; at V5's cost a drop
     * was about two nodes by a wall). Still taken where there's no way down without one, or round is a long way.
     */
    static float DROP_PENALTY = 60.0f, DROP_PENALTY_PER_BLOCK = 10.0f;

    /** What a drop of {@code blocks} costs on top of the time it takes (see {@link #DROP_PENALTY}). */
    static float dropDislike(int blocks) {
        return blocks < 2 ? 0 : DROP_PENALTY + DROP_PENALTY_PER_BLOCK * (blocks - 2);
    }

    private static boolean base_isSlab(float base) {
        return base < JUMP_UP_ONE_BLOCK_TIME;
    }
    private static final float[] FALL_TIMES = new float[257];

    static {
        float distance = 0, velocity = 0;
        int tick = 0;
        for (int target = 1; target <= 256; target++) {
            while (distance < target) {
                velocity = (velocity - 0.08f) * 0.98f;
                distance -= velocity;
                tick++;
            }
            FALL_TIMES[target] = tick;
        }
    }

    static float fallTime(int blocks) {
        if (blocks <= 0) return 0;
        if (blocks >= FALL_TIMES.length) return INF_COST;
        return FALL_TIMES[blocks] + LAND_RECOVERY_TIME;
    }

    public static final float HEURISTIC_WEIGHT = 1.05f;
    public static final float NON_PRIMARY_START_PENALTY = 250f;
    public static final int MAX_ITERATIONS = 500_000;

    private static final int MAX_DIST = 6;
    private static final float[] EDGE_PENALTIES = {24.0f, 19.5f, 16.0f, 11.5f, 5.5f, 3.7f, 0.5f};
    private static final float[] WALL_PENALTIES = {17.0f, 13.5f, 11.0f, 6.5f, 3.0f, 1.5f, 0.2f};
    static final int[] DX = {0, 0, 1, -1, 1, -1, 1, -1};
    static final int[] DZ = {-1, 1, 0, 0, -1, -1, 1, 1};
    private static final int[][] WALK_MOVES = {
            {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0},
            {1, 0, -1}, {-1, 0, -1}, {1, 0, 1}, {-1, 0, 1},
            {0, 1, -1}, {0, 1, 1}, {1, 1, 0}, {-1, 1, 0},
            {0, -1, -1}, {0, -1, 1}, {1, -1, 0}, {-1, -1, 0},
    };

    /**
     * The most blocks up a jump gets you onto a ledge (1: a vanilla jump; the shaft macros set it to the user's
     * jump: a little hill at the end of a walk was taken a block at a time, when one jump gets you up it).
     */
    public static volatile int maxClimb = 1;

    /**
     * {@link #maxClimb} for one search on another thread (the shaft's route shown ahead, worked out beside the walker's
     * own searches): set for the thread, it's that search's, not the walker's. Null: {@link #maxClimb}.
     */
    public static final ThreadLocal<Integer> climbOnThisThread = new ThreadLocal<>();

    /**
     * Where the user jumps up in the shaft walked (from their recordings: {take-off x y z, landing x y z}): a climb onto
     * the line of one of theirs is cheap, another within 8 of it the stop-and-jump (theirs is the way up there). Empty:
     * none known.
     */
    public static volatile List<double[]> likedClimbs = List.of();
    /**
     * The user's own moves in the shaft walked (their recordings' jumps and walk-offs a block and a half or more across, up
     * or down 1.4 or more, no leap): {take-off node x y z, landing node x y z, then where their feet were: take-off x y z,
     * landing x y z}. Ways of their own from the take-off's block (or one beside it) to the landing's.
     */
    public static volatile List<double[]> likedEdges = List.of();

    /** 1: a climb up onto (x, y, z) is on the line of one of the user's ({@link #likedClimbs}); -1: one's within 8; 0: none near. */
    public static int likedClimb(int x, int y, int z) {
        List<double[]> ups = likedClimbs;
        if (ups.isEmpty()) return 0;
        double px = x + 0.5, pz = z + 0.5;
        int out = 0;
        for (double[] u : ups) {
            if (Math.abs(u[4] - y) > 1.0) continue; // (up to its height)
            double ax = u[0], az = u[2], bx = u[3] - ax, bz = u[5] - az, len2 = bx * bx + bz * bz;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * bx + (pz - az) * bz) / len2));
            double d = Math.hypot(px - (ax + bx * t), pz - (az + bz * t));
            if (d <= 1.3) return 1;
            if (d <= 8) out = -1;
        }
        return out;
    }

    /** Set for a thread's searches: the user's moves ({@link #likedEdges}) left out of them (telling which are only walks). */
    public static final ThreadLocal<Boolean> withoutUsersMoves = new ThreadLocal<>();

    /** The user's moves played back here and not landing where theirs did: left out (till a reload). */
    public static final java.util.Set<Long> failedEdges = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** An edge's key: its take-off and landing nodes. */
    public static long edgeKey(double[] e) {
        long k = 17;
        for (int i = 0; i < 6; i++) k = k * 31 + (long) e[i];
        return k;
    }

    /** How high one of the user's own climbs may be (their jump, all but a hair; {@link #maxClimb} keeps 0.3 to spare). */
    public static volatile int likedClimbMax = 0;

    /** Path node flags (path_annotations.cpp). */
    public static final int FLAG_FLUID_FEET = 1, FLAG_FLUID_HEAD = 1 << 1, FLAG_LOW_HEADROOM = 1 << 2,
            FLAG_NEAR_EDGE = 1 << 3, FLAG_NEAR_WALL = 1 << 4, FLAG_STEP_UP_NEXT = 1 << 5, FLAG_DROP_NEXT = 1 << 6,
            FLAG_TIGHT_CORRIDOR = 1 << 7;

    /** Somewhere to keep away from: cost {@code penalty} at it, falling off to a fifth at {@code radiusSq}. */
    public record AvoidZone(int x, int y, int z, int radiusSq, int maxYDiff, float penalty) {}

    /**
     * Blocks that can be mined out of the way (the shaft's: hardstone, ores, ice…) and what mining each costs, in the
     * search's ticks; negative: it can't be. With one, a walk may go through them (the user: "use pathfinder but have
     * mining added on"), never the block underfoot. The walker mines them as it gets to them ({@link V5PathWalker#setDig}).
     */
    public interface Dig {
        float cost(int x, int y, int z);

        /** Held on and it didn't break: not to be planned through again for a while. */
        default void failed(int x, int y, int z) {}
    }

    /** A path (feet positions), each node's flags, which start it came from, and how long it took. */
    public record Result(List<BlockPos> path, int[] flags, int startIndex, int explored, long ms, long signature) {}

    /**
     * The cheapest walk from one of {@code starts} to one of {@code goals}, or null (none found within
     * {@code maxIterations}, or past {@code deadline}, System.currentTimeMillis, 0 for none).
     */
    public static Result find(V5World w, List<BlockPos> starts, Collection<BlockPos> goals, int maxIterations,
                              int moveOrderOffset, List<AvoidZone> avoid, long deadline) {
        return find(w, starts, goals, maxIterations, moveOrderOffset, avoid, deadline, null);
    }

    /** As above, through what {@code dig} says can be mined (null: nothing). */
    public static Result find(V5World w, List<BlockPos> starts, Collection<BlockPos> goals, int maxIterations,
                              int moveOrderOffset, List<AvoidZone> avoid, long deadline, Dig dig) {
        if (w == null || starts.isEmpty() || goals.isEmpty() || maxIterations <= 0) return null;
        long t0 = System.nanoTime();
        Integer own = climbOnThisThread.get();
        final int climbHere = own != null ? own : maxClimb;
        List<double[]> usersMoves = Boolean.TRUE.equals(withoutUsersMoves.get()) ? List.of() : likedEdges;
        Runtime rt = new Runtime(w, goals, avoid == null ? List.of() : avoid);
        rt.dig = dig;
        Nodes n = new Nodes();

        int[][] moves = new int[WALK_MOVES.length][];
        int offset = moveOrderOffset >= 0 ? moveOrderOffset % WALK_MOVES.length : 0;
        for (int i = 0; i < moves.length; i++) moves[i] = WALK_MOVES[(i + offset) % WALK_MOVES.length];

        for (int i = 0; i < starts.size(); i++) {
            BlockPos s = starts.get(i);
            float penalty = i == 0 ? 0 : NON_PRIMARY_START_PENALTY;
            int idx = n.getOrCreate(s.getX(), s.getY(), s.getZ(), rt);
            if (penalty < n.g[idx]) {
                n.parent[idx] = -1;
                n.start[idx] = i;
                n.setCost(idx, penalty);
            }
            if (n.heapPos[idx] == -1) n.add(idx);
            else n.relocate(idx);
        }

        int iterations = 0;
        int[] out = new int[3];
        float[] cost = new float[1];
        while (n.size > 0 && iterations < maxIterations) {
            if ((iterations & 1023) == 0 && deadline > 0 && System.currentTimeMillis() > deadline) return null;
            iterations++;
            int cur = n.poll();
            if (cur < 0) break;
            int cx = n.x[cur], cy = n.y[cur], cz = n.z[cur];
            if (rt.isGoal(cx, cy, cz)) {
                List<BlockPos> path = new ArrayList<>();
                for (int k = cur; k != -1; k = n.parent[k]) path.add(new BlockPos(n.x[k], n.y[k], n.z[k]));
                Collections.reverse(path);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                return new Result(path, nodeFlags(w, path), n.start[cur], iterations, ms, signature(path));
            }
            float g = n.g[cur];
            int startIdx = n.start[cur];
            for (int[] m : moves) {
                if (!rt.walkMove(cx, cy, cz, m, out, cost)) continue;
                if (cost[0] >= INF_COST) continue;
                int before = n.count;
                int ni = n.getOrCreate(out[0], out[1], out[2], rt);
                float newCost = g + cost[0] + n.avoid[ni];
                if (ni == before) {
                    n.parent[ni] = cur;
                    n.start[ni] = startIdx;
                    n.setCost(ni, newCost);
                    n.add(ni);
                } else if (newCost < n.g[ni]) {
                    n.parent[ni] = cur;
                    n.start[ni] = startIdx;
                    n.setCost(ni, newCost);
                    if (n.heapPos[ni] != -1) n.relocate(ni);
                    else n.add(ni);
                }
            }
            // up more than a block in one jump, with a jump that high
            int climb = climbHere;
            // (and the user's own climbs as high as their jump goes: up 6 with a 6.1 jump onto JADE_1's planks by #5)
            int top = climb > 1 && !likedClimbs.isEmpty() ? Math.max(climb, likedClimbMax) : climb;
            for (int d = 0; d < 4 && climb > 1; d++)
                for (int hgt = 2; hgt <= top; hgt++) {
                    if (!rt.ascendHigh(cx, cy, cz, DX[d], DZ[d], hgt, out, cost, hgt > climb)) continue;
                    int before = n.count;
                    int ni = n.getOrCreate(out[0], out[1], out[2], rt);
                    float newCost = g + cost[0] + n.avoid[ni];
                    if (ni == before || newCost < n.g[ni]) {
                        n.parent[ni] = cur;
                        n.start[ni] = startIdx;
                        n.setCost(ni, newCost);
                        if (ni == before || n.heapPos[ni] == -1) n.add(ni);
                        else n.relocate(ni);
                    }
                }
            // the user's own moves here (from their recordings): from the block they took off from (or one beside it) to
            // where they came down, a way of its own — a run and a jump up onto a ledge 5 off, off a bridge where its
            // other edges catch your head (AQUA_2's #3 to #4: "I walk off from -185.3 16.5 -187.7", "I jump from
            // -182.7 20.0 -168.7 to -177.7 25.4 -168.4"), which a block a move can't make
            for (double[] e : usersMoves) {
                if (!failedEdges.isEmpty() && failedEdges.contains(edgeKey(e))) continue;
                int ax = (int) e[0], ay = (int) e[1], az = (int) e[2], bx = (int) e[3], by = (int) e[4], bz = (int) e[5];
                if (ay != cy || Math.abs(ax - cx) > 1 || Math.abs(az - cz) > 1 || (bx == cx && by == cy && bz == cz)) continue;
                // (from their take-off's own block; from one beside it only when theirs can't be stood in — beside it, a
                // fence was between: AMBE_1's #3 to #4 went at their walk-off across the bridge's fence and stuck on it)
                if ((ax != cx || az != cz) && rt.standableHere(ax, ay, az)) continue;
                if (!rt.standableHere(bx, by, bz)) continue;
                float c = SPRINT_ONE_BLOCK_TIME * ((float) Math.hypot(bx - cx, bz - cz) + (ax != cx || az != cz ? 1 : 0)) + 2;
                // (a way down of theirs is a drop too, not taken over the stairs beside it for being theirs — its height theirs to
                // judge: the spot's shown to be safe, AMBE_1's 20 by #2)
                if (cy - by >= 2) c += DROP_PENALTY;
                int before = n.count;
                int ni = n.getOrCreate(bx, by, bz, rt);
                float newCost = g + c + n.avoid[ni];
                if (ni == before || newCost < n.g[ni]) {
                    n.parent[ni] = cur;
                    n.start[ni] = startIdx;
                    n.setCost(ni, newCost);
                    if (ni == before || n.heapPos[ni] == -1) n.add(ni);
                    else n.relocate(ni);
                }
            }
        }
        return null;
    }

    /**
     * Each node's flags (path_annotations.cpp, without the proximity ones): fluid at the feet or head, low headroom
     * (no room over the head), the next node up or down.
     */
    public static int[] nodeFlags(V5World w, List<BlockPos> path) {
        int[] out = new int[path.size()];
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            int flags = 0;
            if (has(w.flags(p.getX(), p.getY(), p.getZ()), FLUID)) flags |= FLAG_FLUID_FEET;
            if (has(w.flags(p.getX(), p.getY() + 1, p.getZ()), FLUID)) flags |= FLAG_FLUID_HEAD;
            if (!passable(w.flags(p.getX(), p.getY() + 2, p.getZ()))) flags |= FLAG_LOW_HEADROOM;
            if (i + 1 < path.size()) {
                int dy = path.get(i + 1).getY() - p.getY();
                if (dy > 0) flags |= FLAG_STEP_UP_NEXT;
                if (dy < 0) flags |= FLAG_DROP_NEXT;
            }
            out[i] = flags;
        }
        return out;
    }

    /** V5's path signature (FNV over the nodes): the same path found again. */
    static long signature(List<BlockPos> path) {
        long hash = -3750763034362895579L;
        for (BlockPos p : path) {
            long h = ((long) p.getX() * 73856093L) ^ ((long) p.getY() * 19349663L) ^ ((long) p.getZ() * 83492791L);
            hash = (hash ^ h) * 1099511628211L;
        }
        return hash;
    }

    /** V5's isSafe: solid below, room for the feet and head. */
    public static boolean standable(V5World w, int x, int y, int z) {
        short below = w.flags(x, y - 1, z);
        return has(below, SOLID) && !thin(below) && passable(w.flags(x, y, z)) && passable(w.flags(x, y + 1, z));
    }

    /** A fence, a wall or something thin (a pane): not stood on top of. (Fences were, as V5 has it; a pane's post can't be landed on.) */
    private static boolean thin(short f) {
        return has(f, FENCE_LIKE);
    }

    /** The search's view of the world: moves, costs, its caches (kept on the world, shared between searches). */
    private static final class Runtime {
        private static final int SAFE = 0, EDGE = 1, WALL = 2;
        final V5World w;
        final int[] goalX, goalY, goalZ;
        final List<AvoidZone> avoid;
        Dig dig;

        Runtime(V5World w, Collection<BlockPos> goals, List<AvoidZone> avoid) {
            this.w = w;
            this.avoid = avoid;
            goalX = new int[goals.size()];
            goalY = new int[goals.size()];
            goalZ = new int[goals.size()];
            int i = 0;
            for (BlockPos g : goals) {
                goalX[i] = g.getX();
                goalY[i] = g.getY();
                goalZ[i] = g.getZ();
                i++;
            }
        }

        boolean isGoal(int x, int y, int z) {
            for (int i = 0; i < goalX.length; i++) if (goalX[i] == x && goalY[i] == y && goalZ[i] == z) return true;
            return false;
        }

        float heuristic(int x, int y, int z) {
            float sprint = SPRINT_ONE_BLOCK_TIME, diagonal = SPRINT_DIAGONAL_TIME;
            float fallPerBlock = fallTime(2) * 0.5f, jumpPerBlock = JUMP_UP_ONE_BLOCK_TIME;
            float reluctance = sprint * 0.35f;
            float best = Float.POSITIVE_INFINITY;
            for (int i = 0; i < goalX.length; i++) {
                long dx = Math.abs((long) x - goalX[i]), dz = Math.abs((long) z - goalZ[i]);
                long dy = (long) y - goalY[i];
                long min = Math.min(dx, dz), max = Math.max(dx, dz);
                float h = min * diagonal + (max - min) * sprint;
                if (dy != 0) {
                    float ady = Math.abs(dy);
                    h += dy > 0 ? dy * fallPerBlock : ady * jumpPerBlock;
                    h += ady * reluctance;
                }
                if (h < best) best = h;
            }
            return Float.isFinite(best) ? best : 0;
        }

        float avoidPenalty(int x, int y, int z) {
            float penalty = 0;
            for (AvoidZone zone : avoid) {
                if (Math.abs(y - zone.y()) > zone.maxYDiff()) continue;
                long dx = x - zone.x(), dz = z - zone.z();
                long distSq = dx * dx + dz * dz;
                if (distSq > zone.radiusSq()) continue;
                float normalized = zone.radiusSq() <= 1 ? 0 : (float) distSq / zone.radiusSq();
                penalty += zone.penalty() * Math.max(0.2f, 1 - normalized);
            }
            return penalty;
        }

        boolean walkMove(int x, int y, int z, int[] m, int[] out, float[] cost) {
            if (m[1] == 0) {
                if (m[0] == 0 || m[2] == 0) return traverse(x, y, z, m[0], m[2], out, cost);
                return diagonal(x, y, z, m[0], m[2], out, cost);
            }
            if (m[1] > 0) return ascend(x, y, z, m[0], m[2], out, cost);
            return descend(x, y, z, m[0], m[2], out, cost);
        }

        private short f(int x, int y, int z) {
            return w.flags(x, y, z);
        }

        private boolean pass(int x, int y, int z) {
            return passable(f(x, y, z));
        }

        /** What getting (x, y, z) out of the way costs: 0 when it's open, the mining when it can be mined, else INF. */
        private float clear(int x, int y, int z) {
            short fl = f(x, y, z);
            if (passable(fl)) return 0;
            if (dig == null || has(fl, FLUID)) return INF_COST;
            float c = dig.cost(x, y, z);
            return c < 0 ? INF_COST : c;
        }

        /**
         * Down in a pit at (x, y, z): a wall 2 high or more (no step out) within 3 blocks across 3 or 4 of the 4 ways
         * round, the floor between at its level.
         */
        private boolean pit(int x, int y, int z) {
            int walled = 0;
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int k = 1; k <= 3; k++) {
                    int cx = x + d[0] * k, cz = z + d[1] * k;
                    if (!pass(cx, y, cz)) {
                        if (!pass(cx, y + 1, cz)) walled++; // (a step of one is walked up out of)
                        break;
                    }
                    if (!floorAt(cx, y - 1, cz)) break; // (on down that way: not walled in)
                }
            }
            return walled >= 3;
        }

        /** Something solid (not a fence) to stand on at (x, y, z). */
        private boolean floorAt(int x, int y, int z) {
            short fl = f(x, y, z);
            return has(fl, SOLID) && !has(fl, FENCE_LIKE);
        }

        boolean traverse(int x, int y, int z, int dx, int dz, int[] out, float[] cost) {
            int tx = x + dx, tz = z + dz;
            if (!isSafe(tx, y, tz)) {
                // mined through: the feet and head cleared, on a floor
                if (dig == null || !floorAt(tx, y - 1, tz)) return false;
                float c = clear(tx, y, tz) + clear(tx, y + 1, tz);
                if (c >= INF_COST) return false;
                set(out, tx, y, tz);
                cost[0] = SPRINT_ONE_BLOCK_TIME + c + pathPenalty(tx, y, tz);
                return true;
            }
            set(out, tx, y, tz);
            cost[0] = SPRINT_ONE_BLOCK_TIME + pathPenalty(tx, y, tz);
            return true;
        }

        boolean diagonal(int x, int y, int z, int dx, int dz, int[] out, float[] cost) {
            int tx = x + dx, tz = z + dz;
            // both sides open; or, by their real shapes, room for you across the corner between them (two panes)
            boolean open = pass(tx, y, z) && pass(x, y, tz) && pass(tx, y + 1, z) && pass(x, y + 1, tz);
            if (!open && !isSafe(tx, y, tz)) return false;
            if (!open && !w.squeezes(x, y, z, dx, dz)) return false;
            if (!isSafe(tx, y, tz)) return false;
            set(out, tx, y, tz);
            cost[0] = SPRINT_DIAGONAL_TIME + (open ? 0 : SQUEEZE_PENALTY) + pathPenalty(tx, y, tz);
            return true;
        }

        boolean ascend(int x, int y, int z, int dx, int dz, int[] out, float[] cost) {
            int tx = x + dx, tz = z + dz;
            float mined = 0;
            if (dig != null) {
                // the room to rise and the step's feet and head mined out where they must be
                mined = clear(x, y + 2, z) + clear(tx, y + 1, tz) + clear(tx, y + 2, tz);
                if (mined >= INF_COST) return false;
            } else if (!passable(f(x, y + 2, z))) return false;
            short destSupport = f(tx, y, tz);
            if (!has(destSupport, SOLID) || has(destSupport, FENCE_LIKE)) return false;
            if (dig == null && (!passable(f(tx, y + 1, tz)) || !passable(f(tx, y + 2, tz)))) return false;
            short srcSupport = f(x, y - 1, z);
            boolean srcBottom = has(srcSupport, SLAB_BOTTOM), destBottom = has(destSupport, SLAB_BOTTOM);
            if (srcBottom && !destBottom) return false;
            boolean srcStair = has(srcSupport, STAIRS_BOTTOM), destStair = has(destSupport, STAIRS_BOTTOM);
            set(out, tx, y + 1, tz);
            float base = destBottom || srcStair || destStair ? SLAB_ASCENT_TIME : JUMP_UP_ONE_BLOCK_TIME;
            // a block right over the step (or over where you jump from): your head meets it on the jump, and you only
            // get up onto the step on the jump's last bit of rise, if at all. Up a staircase dug a block wide, and onto
            // a step under stone by a pit, it jumped and fell back, gave up and went round another way (it looked
            // like a bot changing its mind): that way's taken from the start, when there is one
            if (!base_isSlab(base) && (!passable(f(tx, y + 3, tz)) || !passable(f(x, y + 3, z)))) base += LOW_STEP_PENALTY;
            cost[0] = base + mined + pathPenalty(tx, y + 1, tz);
            return true;
        }

        private int liked(int x, int y, int z) {
            return likedClimb(x, y, z);
        }

        /** Feet at {@code y} in this cell: something to stand on under it and room for you (a run up's cell). */
        private boolean standableHere(int x, int y, int z) {
            short below = f(x, y - 1, z);
            return has(below, SOLID) && !has(below, FENCE_LIKE) && pass(x, y, z) && pass(x, y + 1, z) && !has(f(x, y, z), FLUID);
        }

        /** Up {@code h} blocks onto the ledge beside you in one jump: room over you all the way up, the ledge's top to stand on, room on it. */
        boolean ascendHigh(int x, int y, int z, int dx, int dz, int h, int[] out, float[] cost, boolean onlyLiked) {
            int tx = x + dx, tz = z + dz;
            for (int k = 2; k <= h + 1; k++) if (!pass(x, y + k, z)) return false;
            short support = f(tx, y + h - 1, tz);
            if (!has(support, SOLID) || has(support, FENCE_LIKE)) return false;
            if (!pass(tx, y + h, tz) || !pass(tx, y + h + 1, tz)) return false;
            int liked = liked(tx, y + h, tz);
            if (onlyLiked && liked <= 0) return false;
            // room over the top for your head as you come over the edge (you come over it with your feet up above it): under
            // a pane two blocks over JADE_1's #5's ledge every jump hit it and fell back — you stand there, you don't jump up
            // onto it (one the user's done is theirs to judge)
            if (liked <= 0 && !pass(tx, y + h + 2, tz)) return false;
            set(out, tx, y + h, tz);
            // a jump up of its own (not the user's): V5's stop-and-jump, a block's step up's worth — more with no run up to it
            // (two cells behind at its level, the way it climbs: out of a hole, "try not to drop down into the holes and jump
            // up") or from a ladder (pressed into, it's climbed, slowly: JADE_1's #4 went up the one in the notch, not the open
            // edge the user jumps). At three blocks' sprint it jumped wherever that saved a node or two (into JADE_1's hollow
            // on the way to #1 and out of it: "it shouldn't jump unless it saves a lot of time"); ShaftMove.takeHigh takes a
            // walk with one only when it's much the shorter
            boolean ladder = has(f(x, y, z), CLIMBABLE) || has(f(x, y + 1, z), CLIMBABLE) || has(f(tx, y + h - 1, tz), CLIMBABLE);
            boolean runUp = !ladder && standableHere(x - dx, y, z - dz) && standableHere(x - 2 * dx, y, z - 2 * dz);
            // the user's own jump up (their recording's line), and others near it the long way: up out of the notch by
            // JADE_1's #4, 4 blocks from the open edge the user jumps onto from 10 blocks off, was the cheaper
            float c = liked > 0 ? SPRINT_ONE_BLOCK_TIME + h
                : liked < 0 ? JUMP_UP_ONE_BLOCK_TIME + 2 * h + 10
                : JUMP_UP_ONE_BLOCK_TIME + (runUp ? 0 : 20) + (ladder ? 20 : 0) + 2 * h;
            cost[0] = c + pathPenalty(tx, y + h, tz);
            return true;
        }

        boolean descend(int x, int y, int z, int dx, int dz, int[] out, float[] cost) {
            int tx = x + dx, tz = z + dz;
            if (dig != null && !(pass(tx, y + 1, tz) && pass(tx, y, tz) && pass(tx, y - 1, tz))) {
                // a step down a block, mined out (the way in at your height and the cell below it), onto a floor
                if (!floorAt(tx, y - 2, tz)) return false;
                float c = clear(tx, y + 1, tz) + clear(tx, y, tz) + clear(tx, y - 1, tz);
                if (c >= INF_COST) return false;
                set(out, tx, y - 1, tz);
                cost[0] = WALK_OFF_EDGE_TIME + fallTime(1) + c + pathPenalty(tx, y - 1, tz);
                return true;
            }
            if (!pass(tx, y + 1, tz) || !pass(tx, y, tz) || !pass(tx, y - 1, tz)) return false;
            for (int drop = 1; drop <= 20; drop++) {
                int floorY = y - drop - 1;
                short floor = f(tx, floorY, tz);
                if (passable(floor)) continue;
                if (!has(floor, SOLID) || has(floor, FENCE_LIKE)) return false;
                int destY = floorY + 1;
                float total = WALK_OFF_EDGE_TIME + fallTime(drop);
                if (drop > 3) {
                    int excess = drop - 3;
                    total += excess * excess * 2.0f;
                }
                // down into a pit (walled in 2 high or more within 3 most ways round): only when it's well worth it — it
                // dropped into JADE_1's hollows and jumped out the far side rather than keep to the level round them (the
                // user: "try not to drop down into the holes and jump up", "it still drops in the things")
                if (drop >= 2 && pit(tx, destY, tz)) total += PIT_PENALTY;
                total += dropDislike(drop);
                total += pathPenalty(tx, destY, tz);
                set(out, tx, destY, tz);
                cost[0] = total;
                return true;
            }
            return false;
        }

        private static void set(int[] out, int x, int y, int z) {
            out[0] = x;
            out[1] = y;
            out[2] = z;
        }

        private boolean classified(int x, int y, int z, int bit) {
            long k = V5World.key(x, y, z);
            byte c = w.classes.get(k);
            if ((c & (1 << bit)) != 0) return (c & (1 << (bit + 4))) != 0;
            boolean v = switch (bit) {
                case SAFE -> has(f(x, y - 1, z), SOLID) && !has(f(x, y - 1, z), FENCE_LIKE) && passable(f(x, y, z)) && passable(f(x, y + 1, z));
                case EDGE -> !has(f(x, y, z), SOLID) && !has(f(x, y - 1, z), SOLID) && !has(f(x, y - 2, z), SOLID);
                default -> wallAt(x, y, z);
            };
            c |= (byte) (1 << bit);
            if (v) c |= (byte) (1 << (bit + 4));
            w.classes.put(k, c);
            return v;
        }

        boolean isSafe(int x, int y, int z) {
            return classified(x, y, z, SAFE);
        }

        boolean isEdge(int x, int y, int z) {
            return classified(x, y, z, EDGE);
        }

        boolean isWall(int x, int y, int z) {
            return classified(x, y, z, WALL);
        }

        private boolean wallAt(int x, int y, int z) {
            short head = f(x, y + 1, z);
            boolean wall = has(head, BLOCKING_WALL);
            if (!wall && has(head, SOLID)) {
                short feet = f(x, y, z);
                wall = has(head, SLAB_TOP) || (!has(feet, SLAB_BOTTOM) && !has(feet, STAIRS_BOTTOM));
            } else if (!wall) {
                wall = has(f(x, y, z), BLOCKING_WALL);
            }
            return wall;
        }

        /** The ways that aren't a step up or down a slab or stair (all 8 when none are). */
        private int directionMask(int x, int y, int z) {
            int mask = 0;
            for (int d = 0; d < 8; d++) if (!isStepDirection(x, y, z, DX[d], DZ[d])) mask |= 1 << d;
            return mask == 0 ? 0xFF : mask;
        }

        private boolean isStepDirection(int x, int y, int z, int dx, int dz) {
            int nx = x + dx, nz = z + dz;
            short upper = f(nx, y, nz);
            if ((has(upper, STAIRS_BOTTOM) || has(upper, SLAB_BOTTOM)) && has(upper, SOLID)
                    && passable(f(nx, y + 1, nz)) && passable(f(nx, y + 2, nz))) {
                return true;
            }
            short lower = f(nx, y - 2, nz);
            return (has(lower, STAIRS_BOTTOM) || has(lower, SLAB_BOTTOM)) && has(lower, SOLID)
                    && passable(f(nx, y - 1, nz)) && passable(upper);
        }

        float pathPenalty(int x, int y, int z) {
            long k = V5World.key(x, y, z);
            float cached = w.penalties.get(k);
            if (!Float.isNaN(cached)) return cached;
            int mask = directionMask(x, y, z);
            int edgeDist = MAX_DIST, wallDist = MAX_DIST;
            for (int d = 0; d < 8; d++) {
                if ((mask & (1 << d)) == 0) continue;
                int rayEdge = MAX_DIST, rayWall = MAX_DIST;
                int cx = x, cz = z;
                for (int s = 1; s <= MAX_DIST && (rayEdge == MAX_DIST || rayWall == MAX_DIST); s++) {
                    cx += DX[d];
                    cz += DZ[d];
                    if (rayEdge == MAX_DIST && isEdge(cx, y, cz)) rayEdge = s - 1;
                    if (rayWall == MAX_DIST && isWall(cx, y, cz)) rayWall = s - 1;
                }
                edgeDist = Math.min(edgeDist, rayEdge);
                wallDist = Math.min(wallDist, rayWall);
                if (edgeDist == 0 && wallDist == 0) break;
            }
            float value = EDGE_PENALTIES[Math.max(0, Math.min(MAX_DIST, edgeDist))]
                    + WALL_PENALTIES[Math.max(0, Math.min(MAX_DIST, wallDist))];
            if (has(f(x, y, z), FLUID)) value += 20;
            if (has(f(x, y + 1, z), FLUID)) value += 20;
            w.penalties.put(k, value);
            return value;
        }
    }

    /** The search's nodes, and V5's 4-ary heap of them (by f, then h, then the greater g). */
    private static final class Nodes {
        int count;
        int[] x = new int[4096], y = new int[4096], z = new int[4096], parent = new int[4096], start = new int[4096],
                heapPos = new int[4096];
        float[] g = new float[4096], h = new float[4096], fc = new float[4096], avoid = new float[4096];
        final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        int[] items = new int[4096];
        int size;

        Nodes() {
            index.defaultReturnValue(-1);
        }

        int getOrCreate(int px, int py, int pz, Runtime rt) {
            long k = V5World.key(px, py, pz);
            int idx = index.get(k);
            if (idx != -1) return idx;
            idx = count++;
            if (idx >= x.length) grow();
            index.put(k, idx);
            x[idx] = px;
            y[idx] = py;
            z[idx] = pz;
            g[idx] = Float.POSITIVE_INFINITY;
            h[idx] = rt.heuristic(px, py, pz) * HEURISTIC_WEIGHT;
            fc[idx] = h[idx];
            avoid[idx] = rt.avoid.isEmpty() ? 0 : rt.avoidPenalty(px, py, pz);
            parent[idx] = -1;
            start[idx] = -1;
            heapPos[idx] = -1;
            return idx;
        }

        private void grow() {
            int n = x.length * 2;
            x = Arrays.copyOf(x, n);
            y = Arrays.copyOf(y, n);
            z = Arrays.copyOf(z, n);
            parent = Arrays.copyOf(parent, n);
            start = Arrays.copyOf(start, n);
            heapPos = Arrays.copyOf(heapPos, n);
            g = Arrays.copyOf(g, n);
            h = Arrays.copyOf(h, n);
            fc = Arrays.copyOf(fc, n);
            avoid = Arrays.copyOf(avoid, n);
        }

        void setCost(int idx, float cost) {
            g[idx] = cost;
            fc[idx] = cost + h[idx];
        }

        private boolean less(int a, int b) {
            if (fc[a] != fc[b]) return fc[a] < fc[b];
            if (h[a] != h[b]) return h[a] < h[b];
            if (g[a] != g[b]) return g[a] > g[b];
            return a < b;
        }

        void add(int idx) {
            if (size + 1 >= items.length) items = Arrays.copyOf(items, items.length * 2);
            size++;
            items[size] = idx;
            heapPos[idx] = size;
            siftUp(size);
        }

        void relocate(int idx) {
            int pos = heapPos[idx];
            if (pos <= 0) return;
            siftUp(pos);
        }

        int poll() {
            if (size <= 0) return -1;
            int result = items[1];
            heapPos[result] = -1;
            int last = items[size];
            size--;
            if (size > 0) {
                items[1] = last;
                heapPos[last] = 1;
                siftDown(1);
            }
            return result;
        }

        private void siftUp(int pos) {
            int node = items[pos];
            while (pos > 1) {
                int parentPos = (pos - 2) / 4 + 1;
                int parentNode = items[parentPos];
                if (!less(node, parentNode)) break;
                items[pos] = parentNode;
                heapPos[parentNode] = pos;
                pos = parentPos;
            }
            items[pos] = node;
            heapPos[node] = pos;
        }

        private void siftDown(int pos) {
            int node = items[pos];
            while (true) {
                int first = (pos - 1) * 4 + 2;
                if (first > size) break;
                int child = first;
                int lastChild = Math.min(first + 3, size);
                for (int c = first + 1; c <= lastChild; c++) if (less(items[c], items[child])) child = c;
                int childNode = items[child];
                if (!less(childNode, node)) break;
                items[pos] = childNode;
                heapPos[childNode] = pos;
                pos = child;
            }
            items[pos] = node;
            heapPos[node] = pos;
        }
    }
}
