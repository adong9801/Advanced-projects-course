package com.rustpath.client.pathfinder;


import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Walks the player along a path found by {@link V5Search}.
 *
 * <p>This movement controller comes from the author's pure Java pathfinder mod and is based on the path
 * walker in the open-source V5 client (V5-Client/V5, utils/pathfinder: PathWalker, PathRotations,
 * PathSpline, PathJumps, PathMovement, PathRecovery, PathPrediction). Each client tick it sets the
 * movement keys; each render frame it turns the view.
 *
 * <ul>
 *   <li><b>Steering:</b> forward and sprint are held and the player steers by where it looks. The look point sits 1.8
 *   to 4.5 blocks ahead on a smoothed Catmull-Rom spline through the path's block centres ({@link #lineLookPoints}), as
 *   far ahead as is visible and walkable, closer on bends. {@link V5PathRotation} turns the view toward it. The last
 *   couple of blocks are walked with the movement keys while the view is held.</li>
 *   <li><b>Jumps:</b> at steps up (pre-jumping from a run when a simulated jump clears the step), over gaps, onto deep
 *   snow, at obstacles, and in fluids; never under a low ceiling.</li>
 *   <li><b>Drops:</b> walked or slid off when a simulation says you'll land on the path; otherwise crept up
 *   to and stepped off a tap at a time.</li>
 *   <li><b>Stuck recovery:</b> not moving on the ground, it jumps after 10 ticks, turns back toward the nearest
 *   reachable node after 22, and after 44 backs up and searches again, avoiding the spot.</li>
 *   <li><b>Replanning:</b> it also searches again after 35 ticks without progress along the path or when a block
 *   appears on it. Up to 9 re-searches per walk, each varied so it doesn't find the same path again.</li>
 * </ul>
 *
 * <p>Differences from V5: the spline goes through block centres rather than corners; you've arrived within
 * {@link #arrivalRadius} horizontally and between 1.1 below and 1.5 above the goal; forward is released a slide's
 * length (speed × 1.2) before the end so it doesn't overshoot.
 *
 * <p>Some sections are for features of the Java mod that this one never turns on (mining along the path, replaying the player's
 * recorded moves, AOTV teleports). They're marked where they start and left in place.
 */
public final class V5PathWalker {
    private V5PathWalker() {}

    private static final Minecraft mc = Minecraft.getInstance();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Rust V5 path search");
        t.setDaemon(true);
        return t;
    });

    private static final int MAX_RECALCULATE_ATTEMPTS = 9;
    private static final long SEARCH_TIMEOUT_MS = 25_000;

    // ── state of the current walk ──
    private static boolean active;
    private static List<BlockPos> goals = List.of();
    private static double arrivalRadius = 1.0;
    private static List<BlockPos> path;
    private static int[] pathFlags;
    private static Future<V5Search.Result> search;
    private static V5World capture;
    private static List<BlockPos> captureStarts, captureGoals;
    private static List<V5Search.AvoidZone> captureAvoid;
    private static int captureSeed;
    private static long searchStartedAt;
    private static int retryInTicks = -1;
    private static int recalcAttempts;
    private static boolean retryQueued;
    private static int variantSeed;
    private static long lastSignature;
    private static int sameSignatureCount;
    private static final List<Avoid> avoid = new ArrayList<>();
    private static Float overridePitch;
    private static boolean shiftWalk;
    private static String lastFailure;
    private static boolean succeeded;

    private record Avoid(int x, int y, int z, int radius, float penalty, int[] ttl) {}

    // ════════════════════════════════════════ API ════════════════════════════════════════

    /** Searches for a path to any of {@code to} (feet positions) and walks it. */
    public static void goTo(Collection<BlockPos> to, double radius) {
        stop();
        if (mc.player == null || mc.level == null || to.isEmpty()) return;
        goals = resolveGoals(V5World.shared(), to);
        arrivalRadius = radius > 0 ? radius : 1.0;
        active = true;
        succeeded = false;
        lastFailure = null;
        startSearch();
    }

    /** Walks a path that was already found. If it gets stuck, the new search goes to the path's last node. */
    public static void walk(List<BlockPos> nodes) {
        stop();
        if (mc.player == null || mc.level == null || nodes == null || nodes.isEmpty()) return;
        V5World w = V5World.shared();
        goals = resolveGoals(w, List.of(nodes.get(nodes.size() - 1)));
        arrivalRadius = 1.0;
        active = true;
        succeeded = false;
        lastFailure = null;
        if (nodes.size() < 2) {
            if (reached(mc.player)) {
                finish(true, null);
                return;
            }
            startSearch();
            return;
        }
        setPath(new ArrayList<>(nodes), V5Search.nodeFlags(w, nodes));
    }

    public static void shutdown() { stop(); closeTrace(); executor.shutdownNow(); }

    public static void stop() {
        if (userJump != null) endUserJump();
        dig = null;
        sneakArrive = false;
        keepOut = List.of();
        withoutUsersMoves = false;
        mineTarget = null;
        mineAim = null;
        mineAt = -1;
        // stop() is called often when nothing is walking; leave the keys alone then
        if (!active && search == null && capture == null) return;
        if (mc.player != null) trace(mc.player, "stopped");
        traceFlush();
        resetPath(true);
        active = false;
        releaseKeys();
        mc.options.keyAttack.setDown(false);
    }

    /**
     * Lets the current walk mine through blocks on its path, as {@link V5Search.Dig} allows. Set after {@link #walk};
     * cleared by {@link #stop}. Not used in this mod; kept from the Java mod.
     */
    public static void setDig(V5Search.Dig d) {
        dig = d;
    }

    /** Makes the current walk crouch over its last few blocks. Set after {@link #walk}; cleared by {@link #stop}. */
    public static void setSneakArrive(boolean on) {
        sneakArrive = on;
    }

    private static boolean sneakArrive;

    /**
     * Extra zones the current walk's re-searches must avoid (e.g. a pit the first path was routed around). Set after
     * {@link #walk}; cleared by {@link #stop}.
     */
    public static void setKeepOut(List<V5Search.AvoidZone> zones) {
        keepOut = zones == null ? List.of() : zones;
    }

    private static List<V5Search.AvoidZone> keepOut = List.of();

    /**
     * Leaves the player's recorded moves out of the current walk. Set after {@link #walk}; cleared by
     * {@link #stop}. Not used in this mod; kept from the Java mod.
     */
    public static void setWithoutUsersMoves(boolean on) {
        withoutUsersMoves = on;
    }

    private static boolean withoutUsersMoves;

    // ── mining along the path (not used in this mod; kept from the Java mod) ──
    /** True while the walk has stopped to mine a block on its path (not counted as being stuck). */
    public static boolean isMining() {
        return active && mineTarget != null;
    }

    private static V5Search.Dig dig;
    /** The block being mined, when that started, and the point the view is turned to meanwhile. */
    private static BlockPos mineTarget;
    private static long mineSince;
    private static Vec3 mineAim;

    /** The path node the miner last placed the player at; -1 when not mining. */
    private static int mineAt = -1;

    /**
     * Mines the blocks the path goes through. While mining, this controls the view instead of the look point, which
     * finds nothing visible inside an undug tunnel and swings about. It looks at the block being mined, else the next
     * one to mine, else two nodes ahead, and sticks with one block until it breaks.
     *
     * @return true while mining (mining owns the keys), false while walking on
     */
    private static boolean mineAhead(LocalPlayer p) {
        if (dig == null || path == null) return false;
        // the node you're at: nearest your feet, keeping the previous one unless another is clearly nearer
        int at = 0;
        double bestD = Double.MAX_VALUE, keptD = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            BlockPos n = path.get(i);
            double d = sq(p.getX() - (n.getX() + 0.5)) + sq(p.getZ() - (n.getZ() + 0.5)) + sq((p.getY() - n.getY()) * 0.7);
            if (i == mineAt) keptD = d;
            if (d < bestD) {
                bestD = d;
                at = i;
            }
        }
        if (mineAt >= 0 && mineAt < path.size() && keptD < bestD + 0.3) at = mineAt;
        mineAt = at;
        Vec3 eye = p.getEyePosition();
        // keep the walk's position along its line up to date, or the look point falls behind
        if (rot.pts != null && rot.pts.size() >= 2) rot.pos = Math.max(rot.pos, rot.projectHorizontal(p.getX(), p.getZ(), rot.pos));
        // standing on a block the path goes through (e.g. the floor around a step down broke): search again from here
        if (p.onGround()) {
            for (int i = Math.max(0, at - 1); i < Math.min(path.size() - 1, at + 1); i++)
                for (BlockPos b : stepBlocks(path.get(i), path.get(i + 1)))
                    if (underYou(p, b) && solidAt(b)) {
                        say("stood on {} the way goes through: searching again from here", b.toShortString());
                        endMining();
                        recalculate("stood on what the way goes through");
                        return true;
                    }
        }
        // blocks for this step (into the current node and on to the next), then the following step's
        List<BlockPos> need = new ArrayList<>(), after = new ArrayList<>();
        for (int i = Math.max(0, at - 1); i < Math.min(path.size() - 1, at + 2); i++)
            for (BlockPos b : stepBlocks(path.get(i), path.get(i + 1))) {
                if (!solidAt(b) || need.contains(b) || after.contains(b) || underYou(p, b)) continue;
                (i <= at ? need : after).add(b);
            }
        // nothing of the path in this step, but you're pushing into something breakable: mine that
        if (need.isEmpty() && p.horizontalCollision && p.onGround()) {
            BlockPos next = path.get(Math.min(path.size() - 1, at + 1));
            double dx = next.getX() + 0.5 - p.getX(), dz = next.getZ() + 0.5 - p.getZ(), len = Math.hypot(dx, dz);
            int feet = (int) Math.floor(p.getY() + 0.01);
            for (int up = 1; up >= 0 && len > 0.1; up--) {
                BlockPos f = BlockPos.containing(p.getX() + dx / len * 0.6, feet + up, p.getZ() + dz / len * 0.6);
                if (solidAt(f) && dig.cost(f.getX(), f.getY(), f.getZ()) >= 0) {
                    need.add(f);
                    break;
                }
            }
        }
        // keep the current block while it's still there and still needed
        BlockPos b = mineTarget != null && solidAt(mineTarget) && (need.contains(mineTarget) || after.contains(mineTarget)) ? mineTarget
            : need.isEmpty() ? null : need.get(0);
        if (b == null || !need.contains(b) && Vec3.atCenterOf(b).distanceTo(eye) > 4.4) {
            // walking on; look at the next block to mine if one is near, otherwise along the path
            BlockPos ahead = b != null ? b : after.isEmpty() ? null : after.get(0);
            Vec3 look;
            if (ahead != null && Vec3.atCenterOf(ahead).distanceTo(eye) <= 5.5) {
                look = seenPoint(p, eye, ahead);
                if (look == null) look = Vec3.atCenterOf(ahead);
            } else {
                BlockPos n = path.get(Math.min(path.size() - 1, at + 2));
                look = new Vec3(n.getX() + 0.5, standTop(n) + 1.62, n.getZ() + 0.5);
            }
            mineTarget = null;
            setMineAim(eye, look);
            holdAttackOnBreakable(p);
            nonChange.resetTracking(); // position along the line is updated above, not through the look point
            return false;
        }
        if (Vec3.atCenterOf(b).distanceTo(eye) > 4.4) { // walk on until it's in reach, looking at it
            mineTarget = null;
            setMineAim(eye, Vec3.atCenterOf(b));
            mc.options.keyAttack.setDown(false);
            nonChange.resetTracking();
            return false;
        }
        // aim at the visible point nearest the crosshair; if none is visible, mine what's in front of it
        Vec3 aim = seenPoint(p, eye, b);
        if (aim == null) {
            net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(b),
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
            BlockPos front = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getBlockPos() : null;
            if (front != null && !front.equals(b) && !underYou(p, front) && dig.cost(front.getX(), front.getY(), front.getZ()) >= 0) {
                b = front;
                aim = seenPoint(p, eye, b);
            }
            if (aim == null) aim = Vec3.atCenterOf(b);
        }
        long now = System.currentTimeMillis();
        if (!b.equals(mineTarget)) {
            mineTarget = b;
            mineSince = now;
            say("mining {} at {} (step {} of {})", mc.level.getBlockState(b).getBlock().getName().getString(), b.toShortString(), at, path.size());
        }
        float c = dig.cost(b.getX(), b.getY(), b.getZ());
        if (c < 0 || now - mineSince > 1500 + (long) (c * 100)) { // didn't break in time: search again
            dig.failed(b.getX(), b.getY(), b.getZ());
            say("{} at {} didn't break: searching again", mc.level.getBlockState(b).getBlock().getName().getString(), b.toShortString());
            endMining();
            recalculate("a block on the way didn't break");
            return true;
        }
        // Face the block and hold attack while the crosshair is on something breakable. Hold W into it when it's
        // straight ahead at feet or head height, so you move into the gap as soon as it opens.
        int feetY = (int) Math.floor(p.getY() + 0.01);
        BlockPos next = path.get(Math.min(path.size() - 1, at + 1));
        boolean inFront = (b.getY() == feetY || b.getY() == feetY + 1) && next.getY() == feetY
            && (b.getX() != Math.floor(p.getX()) || b.getZ() != Math.floor(p.getZ()));
        mc.options.keyUp.setDown(inFront);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        int drill = -1;
        if (drill >= 0 && drill < 9) p.getInventory().setSelectedSlot(drill);
        setMineAim(eye, aim);
        holdAttackOnBreakable(p);
        recovery.resetTracking(); // mining isn't being stuck
        nonChange.resetTracking();
        return true;
    }

    private static boolean solidAt(BlockPos b) {
        return !mc.level.getBlockState(b).getCollisionShape(mc.level, b).isEmpty();
    }

    /** Turns the view toward {@code point} (applied each frame in {@link #frame}). */
    private static void setMineAim(Vec3 eye, Vec3 point) {
        mineAim = point;
        rot.lookYaw = (float) Math.toDegrees(Math.atan2(-(point.x - eye.x), point.z - eye.z));
    }

    private static void endMining() {
        mineTarget = null;
        mineAim = null;
        mineAt = -1;
        mc.options.keyAttack.setDown(false);
    }

    /**
     * Holds attack whenever the crosshair is on a breakable block that isn't under the player, as you would while
     * tunnelling. Clicking only on the target block let go between blocks.
     */
    private static void holdAttackOnBreakable(LocalPlayer p) {
        BlockPos on = mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
            ? bh.getBlockPos() : null;
        boolean hold = on != null && !underYou(p, on) && dig.cost(on.getX(), on.getY(), on.getZ()) >= 0;
        if (hold) {
            int drill = -1;
            if (drill >= 0 && drill < 9) p.getInventory().setSelectedSlot(drill);
        }
        mc.options.keyAttack.setDown(hold);
    }

    /** True for a block the player is standing on. Those are never mined. */
    private static boolean underYou(LocalPlayer p, BlockPos b) {
        net.minecraft.world.phys.AABB box = p.getBoundingBox();
        return b.getY() < Math.floor(p.getY() + 0.01) && box.minX < b.getX() + 1 && box.maxX > b.getX()
            && box.minZ < b.getZ() + 1 && box.maxZ > b.getZ();
    }

    /**
     * The point of {@code b} a ray from {@code eye} hits first, within reach: of 9 sample points per face facing the
     * eye, the one closest to the view direction. Null if none is visible.
     */
    private static Vec3 seenPoint(LocalPlayer p, Vec3 eye, BlockPos b) {
        Vec3 look = p.getViewVector(1.0f);
        Vec3 best = null;
        double bestDot = -2;
        double reach = p.blockInteractionRange();
        for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
            Vec3 n = Vec3.atLowerCornerOf(face.getUnitVec3i());
            Vec3 mid = Vec3.atCenterOf(b).add(n.scale(0.5));
            if (eye.subtract(mid).dot(n) <= 0) continue; // face points away from the eye
            for (double u : new double[]{0.5, 0.2, 0.8})
                for (double v : new double[]{0.5, 0.2, 0.8}) {
                    double ox = face.getAxis() == net.minecraft.core.Direction.Axis.X ? 0 : u - 0.5;
                    double oy = face.getAxis() == net.minecraft.core.Direction.Axis.Y ? 0 : (face.getAxis() == net.minecraft.core.Direction.Axis.X ? u : v) - 0.5;
                    double oz = face.getAxis() == net.minecraft.core.Direction.Axis.Z ? 0 : v - 0.5;
                    Vec3 pt = mid.add(ox * 0.98, oy * 0.98, oz * 0.98);
                    if (pt.distanceTo(eye) > reach - 0.2) continue;
                    net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye,
                        pt.add(pt.subtract(eye).normalize().scale(0.05)), net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, p));
                    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !hit.getBlockPos().equals(b)) continue;
                    double dot = pt.subtract(eye).normalize().dot(look);
                    if (dot > bestDot) {
                        bestDot = dot;
                        best = pt;
                    }
                }
        }
        return best;
    }

    /** Blocks to clear between nodes {@code a} and {@code b}: headroom first on a step up, then head, then feet. */
    private static List<BlockPos> stepBlocks(BlockPos a, BlockPos b) {
        List<BlockPos> out = new ArrayList<>(4);
        int dy = b.getY() - a.getY();
        if (dy > 0) {
            out.add(a.above(2));
            out.add(b.above());
            out.add(b);
        } else if (dy < 0) {
            out.add(new BlockPos(b.getX(), a.getY() + 1, b.getZ()));
            out.add(new BlockPos(b.getX(), a.getY(), b.getZ()));
            if (dy == -1) out.add(b);
        } else if (a.getX() == b.getX() || a.getZ() == b.getZ()) {
            out.add(b.above());
            out.add(b);
        }
        return out;
    }

    // ── status and options ──
    public static boolean isActive() {
        return active;
    }

    public static boolean isSearching() {
        return active && (search != null || retryInTicks >= 0 || path == null);
    }

    /** Whether the last walk reached its goal (false if it failed or was stopped). */
    public static boolean succeeded() {
        return succeeded;
    }

    public static String lastFailure() {
        return lastFailure;
    }

    public static List<BlockPos> currentPath() {
        return path;
    }

    /** Index of the path node nearest the player. */
    public static int nearestIndex() {
        return Math.max(0, jumps.lastNearestIndex);
    }

    /** Uses a fixed pitch instead of the look point's (null turns it off). */
    public static void setOverridePitch(Float pitch) {
        overridePitch = pitch;
    }

    /** Sneaks the whole way (no sprinting). */
    public static void setShiftWalk(boolean on) {
        shiftWalk = on;
    }

    /**
     * Where to look on arrival. The view turns there over the last stretch (walked with the movement keys, so the view
     * is free), so whatever comes next can start without stopping to turn. Null leaves the view alone.
     */
    public static void setFinalLook(Vec3 point) {
        finalLook = point;
    }

    private static Vec3 finalLook;

    /** Logs recovery decisions (jumps, re-searches, giving up). */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("V5Walk");

    // ═══════════════════════════════════ the walk tick ═══════════════════════════════════

    public static void tick() {
        if (!active) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            stop();
            return;
        }
        // Only once per client tick. Callers may call this twice a tick, which made
        // every tick counter run at double speed.
        if (player == lastTickPlayer && player.tickCount == lastTickCount) return;
        lastTickPlayer = player;
        lastTickCount = player.tickCount;
        if (path != null) traceTick(player);
        movementTick();
        jumps.tick();
        rot.tick();

        if (retryInTicks >= 0) {
            if (retryInTicks-- > 0) return;
            retryInTicks = -1;
            startSearch();
            return;
        }
        if (capture != null) {
            // World reads happen here on the client thread; the search thread only sees the captured copy.
            if (!capture.captureBatch()) return;
            V5World frozen = capture;
            capture = null;
            var from = captureStarts;
            var to = captureGoals;
            var zones = captureAvoid;
            int seed = captureSeed;
            search = executor.submit(() -> V5Search.find(frozen, from, to,
                    V5Search.MAX_ITERATIONS, seed, zones, System.currentTimeMillis() + 5000));
        }
        if (search != null) {
            if (!search.isDone()) {
                if (System.currentTimeMillis() - searchStartedAt > SEARCH_TIMEOUT_MS) {
                    search.cancel(true);
                    search = null;
                    recalculate("search timed out");
                }
                return;
            }
            V5Search.Result r;
            try {
                r = search.get();
            } catch (Exception e) {
                r = null;
            }
            search = null;
            if (r != null && r.path().size() == 1) {
                finish(true, null); // already in the goal block
                return;
            }
            if (r == null || r.path().isEmpty()) {
                if (reached(player)) {
                    finish(true, null);
                } else if (recalcAttempts > 0 && !retryQueued) {
                    retryQueued = true;
                    retryRecalculate();
                } else {
                    finish(false, "no path found");
                }
                return;
            }
            updateSignature(r.signature());
            setPath(r.path(), r.flags());
        }
        if (path == null) {
            if (search == null && retryInTicks < 0) startSearch();
            return;
        }

        if (reached(player)) {
            finish(true, null);
            return;
        }
        // Standing still on the goal block without counting as arrived (e.g. at a slightly different
        // height): treat it as arrived rather than searching again. Only when level with it; standing
        // above a goal down a drop doesn't count.
        double onGoal = flatToGoal(player, true);
        if (onGoal <= 0.6 && player.onGround()) {
            if (onGoalSince == 0) onGoalSince = System.currentTimeMillis();
            else if (System.currentTimeMillis() - onGoalSince > 500) {
                finish(true, null);
                return;
            }
        } else onGoalSince = 0;
        if (rot.complete && onGoal > FINAL_KEYS && player.onGround()) {
            recalculate("at the end of the path, not at the goal"); // look point at the end but the player isn't
            return;
        }

        rot.begin();
        applyRuntimeHints();
        aotv.tick(player);
        if (++wayCheckTicks >= 10) {
            wayCheckTicks = 0;
            if (wayBlockedAhead(player)) {
                recalculate("a block's on the way now");
                return;
            }
        }
        if (recoveryLookTicks > 0 && (--recoveryLookTicks == 0 || recoveryLook != null
                && Math.hypot(recoveryLook.x - player.getX(), recoveryLook.z - player.getZ()) < 0.6)) {
            recoveryLook = null;
            recoveryLookTicks = 0;
        }
        if (mineAhead(player)) return; // mining a block on the path; nothing else this tick
        jumps.detect(player);
        beginMovement(player);

        if (!recovering() && dropTarget == null) bumpJump(player); // walking off an edge isn't bumping into a step

        if (recalcAttempts > 0 && recovery.hasMadeProgress(player)) {
            recalcAttempts = 0;
            recovery.stop();
        }
        String action = null;
        // Turning on the spot only counts as stuck after 1.5 s, so it can't stand there turning forever.
        turnInPlaceTicks = turningInPlace ? turnInPlaceTicks + 1 : 0;
        // nor is having a screen open, since the movement keys do nothing then
        boolean menu = mc.screen != null;
        // lining up for a recorded move isn't stuck either
        if (turningInPlace && turnInPlaceTicks < 30 || userJump != null || menu) recovery.resetTracking();
        else action = recovery.track(player);
        if (action != null) {
            handleRecovery(action);
            if (action.equals("BACKUP_RECALC")) return;
        }
        // Turning onto the path isn't "no headway" either; counting it re-searched mid-turn and restarted the turn.
        if (turningInPlace && turnInPlaceTicks < 30 || userJump != null || menu) nonChange.resetTracking();
        else if (!recovery.stallActive() && nonChange.track(player, rot.pos)) recalculate("no headway along the path");
        if (active) checkJumpRoom(player);
    }

    /** Called every render frame: updates the look point and turns the view toward it. */
    public static void frame() {
        if (!active || path == null || mc.player == null || mc.level == null) return;
        aotv.checkLanded(mc.player); // catch an AOTV landing this frame, not next tick (AOTV is unused here)
        frameHeld = "screen";
        if (mc.screen != null) return;
        frameHeld = "rotation check";
        frameHeld = "final keys";
        if (finalKeys) { // the last stretch is walked with the keys: hold the view, or turn it toward what comes next
            if (finalLook != null) rot.lookAt(mc.player, finalLook);
            return;
        }
        frameHeld = null;
        // Only one thing turns the view at a time; when mining and walking both turned it, the walk kept restarting its
        // turn and couldn't make it round corners.
        if (mineAim != null) { // mining a block on the path: aim at it while the walk's own look waits
            frameHeld = "mining";
            rot.lookAt(mc.player, mineAim);
            return;
        }
        rot.frame(mc.player);
    }

    // ── walk trace ──
    // One line per tick in logs/rust-path-walk.log (position, facing, target look, current action, keys), for
    // working out afterwards why a walk went wrong.

    /** Why the last frame didn't turn the view toward the look point (null if it did). */
    private static String frameHeld;
    private static java.io.PrintWriter trace;
    private static int traceLines;
    /** The client tick the walk last ran on (tick() can be called twice per tick). */
    private static int lastTickCount = -1;
    private static LocalPlayer lastTickPlayer;
    private static final long TRACE_MAX_BYTES = 8L << 20;

    private static void trace(LocalPlayer p, String line) {
        try {
            if (trace == null) {
                java.nio.file.Path f = mc.gameDirectory.toPath().resolve("logs").resolve("rust-path-walk.log");
                if (java.nio.file.Files.exists(f) && java.nio.file.Files.size(f) > TRACE_MAX_BYTES)
                    java.nio.file.Files.move(f, f.resolveSibling("rust-path-walk.1.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                trace = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(f, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND));
                traceLines = 0;
            }
            trace.println(java.time.LocalTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS) + " " + line);
            if (++traceLines % 40 == 0) trace.flush();
            if (traceLines > 200_000) { // close it; the next write reopens it and rolls it over once it's too big
                trace.close();
                trace = null;
            }
        } catch (Exception e) {
            trace = null;
        }
    }

    /** Logs a message and also writes it into the walk trace. */
    private static void say(String format, Object... args) {
        LOG.info(format, args);
        if (mc.player != null) trace(mc.player, "· " + org.slf4j.helpers.MessageFormatter.arrayFormat(format, args).getMessage());
    }

    private static void traceFlush() {
        if (trace != null) trace.flush();
    }

    /** Closes the trace file. */
    public static void closeTrace() {
        if (trace != null) trace.close();
        trace = null;
    }

    /** Writes this tick's trace line. */
    private static void traceTick(LocalPlayer p) {
        var o = mc.options;
        String keys = (o.keyUp.isDown() ? "W" : "") + (o.keyLeft.isDown() ? "A" : "") + (o.keyDown.isDown() ? "S" : "") + (o.keyRight.isDown() ? "D" : "")
            + (o.keyJump.isDown() ? "J" : "") + (o.keySprint.isDown() ? "R" : "") + (o.keyShift.isDown() ? "C" : "") + (o.keyAttack.isDown() ? "M" : "");
        StringBuilder doing = new StringBuilder();
        if (turningInPlace) doing.append("turn ");
        if (finalKeys) doing.append("final ");
        if (dropTarget != null) doing.append("drop ");
        if (dropCareful != null) doing.append("creep ");
        if (dropCoast) doing.append("coast ");
        if (userJump != null) doing.append(replayAt >= 0 ? "replay@" + replayAt + " " : "toUser ");
        if (stepTarget != null) doing.append("step ");
        if (recovering()) doing.append("recover ");
        if (recoveryLook != null) doing.append("recoveryLook ");
        if (mineTarget != null) doing.append("mine ");
        if (strafeSide != 0) doing.append(strafeSide > 0 ? "strafeR " : "strafeL ");
        if (frameHeld != null) doing.append("held:").append(frameHeld.replace(' ', '_')).append(' ');
        if (search != null) doing.append("searching ");
        Vec3 v = p.getDeltaMovement();
        trace(p, String.format(java.util.Locale.US, "%.2f %.2f %.2f %s v%.2f,%.2f yaw %.1f pitch %.1f look %.1f %.1f at %.1f/%d node %d %s| %s",
            p.getX(), p.getY(), p.getZ(), p.onGround() ? "G" : "A", Math.hypot(v.x, v.z), v.y, net.minecraft.util.Mth.wrapDegrees(p.getYRot()), p.getXRot(),
            rot.lookYaw, rot.lookPitch, rot.pos, rot.lastIndex(), path == null ? -1 : nodeAt(p), doing, keys));
    }

    private static void setPath(List<BlockPos> p, int[] flags) {
        if (!p.isEmpty()) { // logged so a failed walk can be read back later
            StringBuilder nodes = new StringBuilder();
            for (int i = 0; i < Math.min(p.size(), 60); i++) {
                BlockPos n = p.get(i);
                nodes.append(n.getX()).append(',').append(n.getY()).append(',').append(n.getZ()).append(' ');
            }
            say("path of {} nodes to {}: {}{}", p.size(), p.get(p.size() - 1).toShortString(), nodes, p.size() > 60 ? "…" : "");
            if (mc.player != null) trace(mc.player, "path of " + p.size() + " nodes: " + nodes + (p.size() > 60 ? "…" : ""));
        }
        path = p;
        pathFlags = flags;
        V5World sw = V5World.shared();
        standableAtStart = new boolean[p.size()];
        for (int i = 0; i < p.size() && sw != null; i++) {
            standableAtStart[i] = V5Search.standable(sw, p.get(i).getX(), p.get(i).getY(), p.get(i).getZ());
        }
        rot.reset();
        rot.pts = lineLookPoints(p);
        jumps.invalidateNearest();
    }

    /**
     * Look points along the line drawn on the ground (PathRenderer's Catmull-Rom curve through every node's centre),
     * one block apart, at eye height above where you'd stand. Following the drawn line works better than the earlier
     * straightened, thinned line, which turned too early and cut corners.
     */
    public static List<Vec3> lineLookPoints(List<BlockPos> all) {
        List<Vec3> out = new ArrayList<>();
        if (all.isEmpty()) return out;
        boolean real = mc != null && mc.level != null;
        // Smooth out the grid's one-block staircase on diagonals (walked node by node it zig-zags), staying within
        // LINE_SLACK of the path and keeping steps up and drops where they are.
        List<BlockPos> nodes = smoothLine(all, real);
        int n = nodes.size();
        double[] x = new double[n], y = new double[n], z = new double[n];
        for (int i = 0; i < n; i++) {
            BlockPos b = nodes.get(i);
            x[i] = b.getX() + 0.5;
            z[i] = b.getZ() + 0.5;
            y[i] = (real ? standTop(b) : b.getY()) + 1.62;
        }
        List<Vec3> fine = new ArrayList<>();
        fine.add(new Vec3(x[0], y[0], z[0]));
        for (int i = 0; i < n - 1; i++) {
            int i0 = Math.max(0, i - 1), i3 = Math.min(n - 1, i + 2);
            for (int k = 1; k <= 6; k++) {
                double t = k / 6.0, ly = y[i] + (y[i + 1] - y[i]) * t;
                Vec3 c = new Vec3(catmullRom(x[i0], x[i], x[i + 1], x[i3], t), ly, catmullRom(z[i0], z[i], z[i + 1], z[i3], t));
                // the curve can swing slightly past a tight corner; if that puts it
                // inside a block, use the straight line
                if (real && insideBlock(c)) c = new Vec3(x[i] + (x[i + 1] - x[i]) * t, ly, z[i] + (z[i + 1] - z[i]) * t);
                fine.add(c);
            }
        }
        out.add(fine.get(0));
        double along = 0;
        for (int i = 1; i < fine.size(); i++) {
            along += fine.get(i).distanceTo(fine.get(i - 1));
            if (along >= 1.0 || i == fine.size() - 1) {
                out.add(fine.get(i));
                along = 0;
            }
        }
        return out;
    }

    /** How far sideways the walked line may stray from the path's nodes. */
    private static final double LINE_SLACK = 0.55;

    /**
     * Simplifies the path to the nodes the walked line goes through (Douglas-Peucker): the fewest nodes such that the
     * line between two kept ones stays within {@link #LINE_SLACK} of every skipped node and the body fits along it.
     * Nodes on either side of a step up or a drop are always kept.
     */
    public static List<BlockPos> smoothLine(List<BlockPos> nodes, boolean real) {
        int n = nodes.size();
        if (n < 3) return nodes;
        double[] top = new double[n];
        for (int i = 0; i < n; i++) top[i] = real ? standTop(nodes.get(i)) : nodes.get(i).getY();
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        for (int i = 1; i < n; i++) {
            if (Math.abs(top[i] - top[i - 1]) > 0.6) keep[i] = keep[i - 1] = true;
        }
        java.util.ArrayDeque<int[]> spans = new java.util.ArrayDeque<>();
        int from = 0;
        for (int i = 1; i < n; i++) {
            if (!keep[i]) continue;
            if (i - from > 1) spans.push(new int[]{from, i});
            from = i;
        }
        while (!spans.isEmpty()) {
            int[] sp = spans.pop();
            int a = sp[0], b = sp[1];
            double ax = nodes.get(a).getX() + 0.5, az = nodes.get(a).getZ() + 0.5;
            double dx = nodes.get(b).getX() + 0.5 - ax, dz = nodes.get(b).getZ() + 0.5 - az, len = Math.hypot(dx, dz);
            int worst = -1;
            double most = -1;
            for (int k = a + 1; k < b; k++) {
                double kx = nodes.get(k).getX() + 0.5 - ax, kz = nodes.get(k).getZ() + 0.5 - az;
                double off = len < 1e-6 ? Math.hypot(kx, kz) : Math.abs(kx * dz - kz * dx) / len;
                // ...counting height too, or on a gentle snow slope the line cut through the slope
                // and the walker couldn't see along it
                double t = len < 1e-6 ? 0 : Math.max(0, Math.min(1, (kx * dx + kz * dz) / (len * len)));
                double upOff = Math.abs(top[k] - (top[a] + (top[b] - top[a]) * t));
                off = Math.max(off, upOff * (LINE_SLACK / 0.4));
                if (off > most) {
                    most = off;
                    worst = k;
                }
            }
            // ...or further off on a level span with floor under the whole body and nothing to run into. The search's
            // wall and edge costs make it weave down open tunnels; walk those as one straight line.
            boolean near = most <= LINE_SLACK || real && flatSpan(top, a, b)
                    && floorAlong(new Vec3(ax, top[a], az), new Vec3(ax + dx, top[a], az + dz), top[a], 0.45);
            boolean clear = near && (!real || cutClear(new Vec3(ax, Math.max(top[a], top[b]) - 1, az),
                    new Vec3(ax + dx, Math.max(top[a], top[b]) - 1, az + dz)));
            if (clear || worst < 0) continue;
            keep[worst] = true;
            if (worst - a > 1) spans.push(new int[]{a, worst});
            if (b - worst > 1) spans.push(new int[]{worst, b});
        }
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(nodes.get(i));
        return out;
    }

    /** True if every node from {@code a} to {@code b} stands at about the same height (within a snow layer or two). */
    private static boolean flatSpan(double[] top, int a, int b) {
        for (int k = a + 1; k <= b; k++) if (Math.abs(top[k] - top[a]) > 0.3) return false;
        return true;
    }

    /**
     * True if a straight, level walk from {@code from} to {@code to} has floor at about
     * {@code top} (within 0.3) all the way, under the middle and {@code margin} out each side,
     * with nothing in the feet's cell: no edge, hole or step.
     */
    static boolean floorAlong(Vec3 from, Vec3 to, double top, double margin) {
        Level level = mc.level;
        if (level == null) return false;
        double dx = to.x - from.x, dz = to.z - from.z;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / 0.25));
        int feet = net.minecraft.util.Mth.floor(top + 0.01);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i <= steps; i++) {
            double x = from.x + dx * i / steps, z = from.z + dz * i / steps;
            for (double[] o : new double[][] {{0, 0}, {margin, 0}, {-margin, 0}, {0, margin}, {0, -margin},
                    {margin, margin}, {margin, -margin}, {-margin, margin}, {-margin, -margin}}) {
                int cx = net.minecraft.util.Mth.floor(x + o[0]), cz = net.minecraft.util.Mth.floor(z + o[1]);
                if (!seen.add(BlockPos.asLong(cx, feet, cz))) continue;
                if (!groundAt(level, cx, feet, cz, top)) return false;
            }
        }
        return true;
    }

    /**
     * True if column ({@code x}, {@code z}) has floor at about {@code top}, in the feet's cell (e.g. snow) or the
     * one below. Fences and walls don't count.
     */
    private static boolean groundAt(Level level, int x, int feet, int z, double top) {
        BlockPos in = new BlockPos(x, feet, z);
        var s = level.getBlockState(in).getCollisionShape(level, in);
        if (!s.isEmpty()) return Math.abs(feet + s.max(Direction.Axis.Y) - top) <= 0.3;
        BlockPos under = in.below();
        var u = level.getBlockState(under).getCollisionShape(level, under);
        return !u.isEmpty() && Math.abs(under.getY() + u.max(Direction.Axis.Y) - top) <= 0.3
                && !level.getBlockState(under).is(net.minecraft.tags.BlockTags.FENCES) && !level.getBlockState(under).is(net.minecraft.tags.BlockTags.WALLS);
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    /** The most nodes a straight cut may span. */
    private static final int STRAIGHTEN_MAX = 10;

    /**
     * Cuts the path's corners on the level wherever every point along the cut (and 0.3 to each side) is standable. Not
     * called at the moment (the look line uses {@link #smoothLine}); kept from the Java mod.
     */
    static List<BlockPos> straighten(List<BlockPos> p) {
        V5World w = V5World.shared();
        if (w == null || p.size() < 3) return p;
        List<BlockPos> out = new ArrayList<>();
        out.add(p.get(0));
        int i = 0;
        while (i < p.size() - 1) {
            int best = i + 1;
            for (int j = Math.min(p.size() - 1, i + STRAIGHTEN_MAX); j > i + 1; j--) {
                if (level(p, i, j) && straightWalk(w, p.get(i), p.get(j))) {
                    best = j;
                    break;
                }
            }
            out.add(p.get(best));
            i = best;
        }
        return out;
    }

    private static boolean level(List<BlockPos> p, int from, int to) {
        int y = p.get(from).getY();
        for (int k = from + 1; k <= to; k++) if (p.get(k).getY() != y) return false;
        return true;
    }

    private static boolean straightWalk(V5World w, BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5, az = a.getZ() + 0.5, dx = b.getX() + 0.5 - ax, dz = b.getZ() + 0.5 - az;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / 0.25));
        int y = a.getY();
        for (int s = 0; s <= steps; s++) {
            double x = ax + dx * s / steps, z = az + dz * s / steps;
            for (double[] o : AROUND_BODY) { // check the body's corners too, or a diagonal cut clips block corners
                if (!V5Search.standable(w, (int) Math.floor(x + o[0]), y, (int) Math.floor(z + o[1]))) return false;
            }
        }
        return true;
    }

    private static void finish(boolean ok, String why) {
        if (!ok) say("gave up: {}", why);
        if (mc.player != null) trace(mc.player, ok ? "done" : "gave up: " + why);
        traceFlush();
        succeeded = ok;
        lastFailure = why;
        if (!ok && why != null && mc.player != null && true) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Pathfinder] Path failed: " + why));
        }
        resetPath(true);
        active = false;
        releaseKeys();
    }

    // ═══════════════════════════════════ searching ═══════════════════════════════════

    private static void startSearch() {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;
        V5World mainWorld = V5World.shared();
        List<BlockPos> starts = new ArrayList<>();
        BlockPos start = playerStart(mainWorld, player);
        BlockPos variant = variantStart(mainWorld, start, variantSeed);
        if (variant != null) starts.add(variant);
        // the blocks your body is in as well
        for (BlockPos b : bodyStarts(mainWorld, player)) if (!starts.contains(b)) starts.add(b);
        if (!starts.contains(start)) starts.add(start);
        List<V5Search.AvoidZone> zones = consumeAvoidZones();
        zones.addAll(keepOut);
        List<BlockPos> to = goals;
        int seed = variantSeed;
        try {
            capture = new V5World(level);
            capture.beginCapture(starts, to);
            captureStarts = List.copyOf(starts);
            captureGoals = List.copyOf(to);
            captureAvoid = List.copyOf(zones);
            captureSeed = seed;
            searchStartedAt = System.currentTimeMillis();
        } catch (IllegalArgumentException e) {
            capture = null;
            finish(false, e.getMessage());
        }
    }

    /**
     * Where a walk starts: the standable blocks your body overlaps, nearest first, falling back to the block under your
     * middle as V5 picks it. Using only the middle's block failed next to a fence post: it wasn't standable and the
     * start snapped to a block a few blocks away.
     */
    public static List<BlockPos> bodyStarts(V5World w, LocalPlayer player) {
        List<BlockPos> out = new ArrayList<>();
        if (w == null || player == null) return out;
        int y = (int) Math.round(player.getY());
        // Crouched at an edge, your box may only just touch the block holding you up;
        // if nothing was found, try slightly wider.
        for (double hw : new double[]{player.getBbWidth() / 2 - 0.01, player.getBbWidth() / 2 + 0.02}) {
            for (int x = (int) Math.floor(player.getX() - hw); x <= (int) Math.floor(player.getX() + hw); x++)
                for (int z = (int) Math.floor(player.getZ() - hw); z <= (int) Math.floor(player.getZ() + hw); z++)
                    for (int dy = 1; dy >= -1; dy--)
                        if (V5Search.standable(w, x, y + dy, z)) {
                            BlockPos b = new BlockPos(x, y + dy, z);
                            if (!out.contains(b)) out.add(b);
                            break;
                        }
            if (!out.isEmpty()) break;
        }
        out.sort(java.util.Comparator.comparingDouble(b -> Math.hypot(b.getX() + 0.5 - player.getX(), b.getZ() + 0.5 - player.getZ())));
        if (out.isEmpty()) out.add(playerStart(w, player));
        return out;
    }

    /** Where you stand, as V5 picks it: the first standable cell from just above your rounded height down. */
    static BlockPos playerStart(V5World w, LocalPlayer player) {
        int x = (int) Math.floor(player.getX()), z = (int) Math.floor(player.getZ());
        int y = (int) Math.round(player.getY());
        for (int dy = 1; dy >= -2; dy--) if (w != null && V5Search.standable(w, x, y + dy, z)) return new BlockPos(x, y + dy, z);
        for (int i = 0; i < 5; i++) {
            if (w != null && V5Flags.passable(w.flags(x, y - i, z))) return new BlockPos(x, y - i, z);
        }
        return new BlockPos(x, (int) Math.round(player.getY()), z);
    }

    private static final int[][] VARIANT_OFFSETS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
            {2, 0}, {0, 2}, {-2, 0}, {0, -2}};

    /** A start one step away, to get a different path when the same one keeps failing (V5's getVariantWalkStart). */
    private static BlockPos variantStart(V5World w, BlockPos p, int seed) {
        if (seed <= 0 || w == null) return null;
        int from = seed % VARIANT_OFFSETS.length;
        for (int i = 0; i < VARIANT_OFFSETS.length; i++) {
            int[] o = VARIANT_OFFSETS[(from + i) % VARIANT_OFFSETS.length];
            if (V5Search.standable(w, p.getX() + o[0], p.getY(), p.getZ() + o[1])) {
                return new BlockPos(p.getX() + o[0], p.getY(), p.getZ() + o[1]);
            }
        }
        return null;
    }

    /**
     * Converts goals to V5's convention, where the feet stand on top of a slab, stair or snow layer rather than inside
     * it: the given spot, or the standable one just above or below.
     */
    public static List<BlockPos> resolveGoals(V5World w, Collection<BlockPos> to) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos g : to) {
            BlockPos pick = g;
            if (w != null && !V5Search.standable(w, g.getX(), g.getY(), g.getZ())) {
                if (V5Search.standable(w, g.getX(), g.getY() + 1, g.getZ())) pick = g.above();
                else if (V5Search.standable(w, g.getX(), g.getY() - 1, g.getZ())) pick = g.below();
            }
            if (!out.contains(pick)) out.add(pick);
        }
        return out;
    }

    private static void recalculate(String reason) {
        say("searching again ({}), attempt {}", reason, recalcAttempts + 1);
        if (mc.player != null) trace(mc.player, "searching again: " + reason);
        int intensity = sameSignatureCount >= 2 ? 2 : 1;
        variantSeed += intensity;
        addAvoidAtPlayer(2 + Math.min(1, intensity), 34 + intensity * 8, 2);
        recalcAttempts++;
        variantSeed = Math.max(variantSeed, recalcAttempts);
        retryQueued = false;
        if (recalcAttempts > MAX_RECALCULATE_ATTEMPTS) {
            finish(false, "stuck (" + reason + "), searched " + MAX_RECALCULATE_ATTEMPTS + " times");
            return;
        }
        schedulePathRetry(3);
    }

    private static void retryRecalculate() {
        variantSeed = Math.max(variantSeed, recalcAttempts);
        schedulePathRetry(5);
    }

    private static void schedulePathRetry(int delay) {
        resetPath(false);
        releaseKeys();
        retryInTicks = delay;
    }

    private static void updateSignature(long signature) {
        if (signature == lastSignature) sameSignatureCount++;
        else {
            lastSignature = signature;
            sameSignatureCount = 0;
        }
        if (sameSignatureCount >= 2 && recalcAttempts > 0) {
            variantSeed++;
            addAvoidAtPlayer(3, 48, 2);
        }
    }

    /** Makes the spot where you got stuck, and the blocks ahead of it, expensive for the next couple of searches. */
    private static void addAvoidAtPlayer(int radius, float penalty, int ttl) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        int x = (int) Math.floor(p.getX()), y = (int) Math.floor(p.getY()), z = (int) Math.floor(p.getZ());
        avoid.add(new Avoid(x, y, z, 1, Math.max(8, penalty * 0.45f), new int[]{Math.max(1, ttl)}));
        double yawRad = Math.toRadians(p.getYRot() + 90);
        int fx = (int) Math.round(Math.cos(yawRad)), fz = (int) Math.round(Math.sin(yawRad));
        if (fx == 0 && fz == 0) fx = 1;
        avoid.add(new Avoid(x + fx, y, z + fz, radius, penalty, new int[]{ttl}));
        avoid.add(new Avoid(x + fx * 2, y, z + fz * 2, radius, penalty * 1.15f, new int[]{ttl}));
        avoid.add(new Avoid(x + fz, y, z - fx, Math.max(1, radius - 1), penalty * 0.7f, new int[]{ttl}));
        avoid.add(new Avoid(x - fz, y, z + fx, Math.max(1, radius - 1), penalty * 0.7f, new int[]{ttl}));
    }

    private static List<V5Search.AvoidZone> consumeAvoidZones() {
        avoid.removeIf(a -> a.ttl[0] <= 0);
        List<V5Search.AvoidZone> zones = new ArrayList<>();
        for (Avoid a : avoid) {
            int r = Math.max(1, a.radius);
            zones.add(new V5Search.AvoidZone(a.x, a.y, a.z, r * r, 2, Math.max(0, a.penalty)));
            a.ttl[0]--;
        }
        avoid.removeIf(a -> a.ttl[0] <= 0);
        return zones;
    }

    /** Drops everything about the current path. With {@code all}, the goal and the retry state go too. */
    private static void resetPath(boolean all) {
        capture = null;
        if (search != null) search.cancel(true);
        search = null;
        retryInTicks = -1;
        noForwardSince = 0; // otherwise the next walk's "forward released" timing counts from the previous walk
        path = null;
        pathFlags = null;
        stepTarget = null;
        dropTarget = null;
        dropCareful = null;
        dropCoast = false;
        dropWalked = null;
        dropNoCoast = null;
        recoveryLook = null;
        recoveryLookTicks = 0;
        rot.reset();
        jumps.reset();
        aotv.reset();
        forceJumpTicks = 0;
        backupTicks = 0;
        backupThen = null;
        finalKeys = false;
        onGoalSince = 0;
        if (all) finalLook = null;
        if (all) {
            recovery.stop();
            nonChange.stop();
            avoid.clear();
            recalcAttempts = 0;
            retryQueued = false;
            variantSeed = 0;
            lastSignature = 0;
            sameSignatureCount = 0;
        } else {
            recovery.resetTracking();
            nonChange.resetTracking();
        }
    }

    // ═══════════════════════════════════ arrival ═══════════════════════════════════

    private static boolean reached(LocalPlayer p) {
        for (BlockPos g : goals) {
            double dx = p.getX() - (g.getX() + 0.5), dz = p.getZ() - (g.getZ() + 0.5);
            if (dx * dx + dz * dz > arrivalRadius * arrivalRadius) continue;
            double dy = p.getY() - g.getY();
            if (dy < -1.1 || dy > 1.5) continue;
            if (standTop(g) - p.getY() > 0.6) continue; // still below it: a goal up a step isn't reached yet
            if (p.onGround()) return true;
        }
        return false;
    }

    private static double flatToGoal(LocalPlayer p) {
        return flatToGoal(p, false);
    }

    /** Horizontal distance to the nearest goal. With {@code level}, only goals at about your height count. */
    private static double flatToGoal(LocalPlayer p, boolean level) {
        double best = Double.MAX_VALUE;
        for (BlockPos g : goals) {
            double dy = p.getY() - g.getY();
            if (level && (dy < -1.1 || dy > 1.5)) continue;
            best = Math.min(best, Math.hypot(p.getX() - (g.getX() + 0.5), p.getZ() - (g.getZ() + 0.5)));
        }
        return best;
    }

    private static void applyRuntimeHints() {
        if (pathFlags == null || pathFlags.length == 0 || recovery.stallActive()) return;
        int i = Math.max(0, Math.min(pathFlags.length - 1, jumps.lastNearestIndex));
        if ((pathFlags[i] & V5Search.FLAG_LOW_HEADROOM) != 0) {
            jumps.suppress(4);
            // Short look-ahead under a low ceiling, but not V5's 0.1: a look point at your own position flips direction
            // almost every frame and the view shakes.
            rot.setTemporaryLookahead(Rot.MIN_LOOKAHEAD, 8);
        }
    }

    /** How many times any walk has got stuck so far (callers can use it to switch strategy). */
    private static int stuckEvents;

    public static int stuckEvents() {
        return stuckEvents;
    }

    private static void handleRecovery(String action) {
        stuckEvents++;
        if (mc.player != null) say("stuck at {} {} {}: {}", String.format("%.1f", mc.player.getX()),
                String.format("%.1f", mc.player.getY()), String.format("%.1f", mc.player.getZ()), action);
        jumps.invalidateNearest();
        switch (action) {
            case "JUMP" -> forceJumpTicks = 4;
            case "CLOSE_LOOK" -> {
                // Get back onto the path by looking at the nearest node ahead that a straight walk reaches (V5's look
                // point at your feet swung the view around). If there's none, back up and search again.
                Vec3 to = mc.player != null ? nodeToRejoin(mc.player) : null;
                if (to != null) {
                    recoveryLook = to;
                    recoveryLookTicks = 40;
                } else {
                    addAvoidAtPlayer(2, 42, 2);
                    backupTicks = 15;
                    backupThen = () -> recalculate("stuck, nowhere on the way to walk straight to");
                }
            }
            case "BACKUP_RECALC" -> {
                addAvoidAtPlayer(2, 42, 2);
                backupTicks = 15;
                backupThen = () -> recalculate("stuck, backed up");
            }
            default -> {}
        }
    }

    /** During recovery: the path node being walked back to, and for how many more ticks. */
    private static Vec3 recoveryLook;
    private static int recoveryLookTicks;

    /** Index of the path node you're at: the nearest horizontally of those within 2 blocks vertically; -1 if none. */
    private static int nodeAt(LocalPlayer p) {
        int at = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; path != null && i < path.size(); i++) {
            BlockPos n = path.get(i);
            if (Math.abs(n.getY() - p.getY()) > 2) continue;
            double d = sq(n.getX() + 0.5 - p.getX()) + sq(n.getZ() + 0.5 - p.getZ());
            if (d < best) {
                best = d;
                at = i;
            }
        }
        return at;
    }

    /** The nearest of the next few nodes that a straight walk from here reaches, or null. */
    private static Vec3 nodeToRejoin(LocalPlayer p) {
        int at = nodeAt(p);
        if (at < 0) return null;
        for (int k = at; k < Math.min(path.size(), at + 7); k++) {
            BlockPos n = path.get(k);
            Vec3 c = new Vec3(n.getX() + 0.5, standTop(n) + 1.0, n.getZ() + 0.5);
            if (Math.hypot(c.x - p.getX(), c.z - p.getZ()) < 0.6) continue;
            if (rot.bodyClear(c)) return c;
        }
        return null;
    }

    /**
     * True if one of the next 8 nodes, standable when the path was found, has since been blocked. Searching again beats
     * walking into it. Uses V5World's shared snapshot, so it can be up to a second old.
     */
    private static boolean wayBlockedAhead(LocalPlayer p) {
        V5World w = V5World.shared();
        if (w == null || path == null || path.size() < 2) return false;
        int at = nodeAt(p);
        if (at < 0) return false;
        for (int k = at + 1; k < Math.min(path.size(), at + 9); k++) {
            BlockPos n = path.get(k);
            // only nodes that were standable when the path was found
            if (standableAtStart == null || k >= standableAtStart.length || !standableAtStart[k]) continue;
            if (!V5Search.standable(w, n.getX(), n.getY(), n.getZ())) {
                say("the way's blocked at {} now", n.toShortString());
                return true;
            }
        }
        return false;
    }

    private static int wayCheckTicks;
    /** Which path nodes were standable when the path was found. The others (climbs, the goal) aren't checked. */
    private static boolean[] standableAtStart;

    // ═══════════════════════════════════ movement (PathMovement) ═══════════════════════════════════

    private static int forceJumpTicks;
    private static int backupTicks;
    private static Runnable backupThen;

    private static boolean recovering() {
        return forceJumpTicks > 0 || backupTicks > 0;
    }

    private static void movementTick() {
        if (forceJumpTicks > 0) {
            mc.options.keyJump.setDown(true);
            if (--forceJumpTicks == 0) mc.options.keyJump.setDown(false);
            if (mc.player != null) checkJumpRoom(mc.player);
        }
        if (backupTicks > 0) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(true);
            mc.options.keySprint.setDown(false);
            if (--backupTicks == 0) {
                mc.options.keyDown.setDown(false);
                Runnable then = backupThen;
                backupThen = null;
                if (then != null) then.run();
            }
        }
    }

    private static void beginMovement(LocalPlayer p) {
        if (backupTicks > 0) return;
        mc.options.keyShift.setDown(shiftWalk);
        // A drop is next. Most drops are just walked off (or slid off with W released, see dropCoast); dropAhead only
        // returns a target when the landing spot is exact. Then it creeps to the edge, steps off toward the middle of
        // the landing column and falls with nothing pressed.
        dropCoast = false;
        BlockPos drop = stepTarget == null ? dropAhead(p) : null;
        if (drop != null) {
            finalKeys = false;
            turningInPlace = false;
            strafeSide = 0;
            mc.options.keySprint.setDown(false);
            double dx = drop.getX() + 0.5 - p.getX(), dz = drop.getZ() + 0.5 - p.getZ();
            var v = p.getDeltaMovement();
            double speed = Math.hypot(v.x, v.z);
            boolean tapped = dropTapped;
            dropTapped = false;
            boolean hanging = !p.onGround() && Math.abs(v.y) < 0.01 && speed < 0.01; // caught on an edge
            if (!p.onGround() && !hanging) {
                // Falling: press nothing except to stop drifting sideways. Air keeps 91% of horizontal speed per tick,
                // so steering while falling carried it into the far wall of a narrow shaft.
                mc.options.keyShift.setDown(false);
                releaseMoveKeys();
                // cancel the drift so the body stays over the landing column; a few tenths
                // off, its edge caught on the next column
                double wantX = net.minecraft.util.Mth.clamp(dx * 0.25, -0.08, 0.08), wantZ = net.minecraft.util.Mth.clamp(dz * 0.25, -0.08, 0.08);
                double ex = wantX - v.x, ez = wantZ - v.z;
                // not on a ladder: pressing a key there would climb it
                if (Math.hypot(ex, ez) > 0.015 && !p.onClimbable()) pressAlong(p, ex, ez);
                return;
            }
            double dist = Math.hypot(dx, dz);
            if (dist > 1.05 && dropCreepStill < 4) {
                // creep up to the edge crouched; at walking speed it flew across a 3-wide shaft
                mc.options.keyShift.setDown(true);
                Approach.keys(mc, dx, dz, 0.12);
                // count ticks at the edge without getting closer; after a few, step off a tap at a time
                dropCreepStill = dist > dropLastDist - 0.02 ? dropCreepStill + 1 : 0;
                dropLastDist = dist;
            } else if (dropTapTicks > 6) {
                // tapped toward the drop and still not off the edge after 6 ticks: hold the key toward it (logged once)
                if (dropTapTicks == 7) say("not off the edge at {} {} {} tapping toward the drop to {} (on the ground {}, hanging {}, crouched {}): held toward it",
                    String.format("%.2f", p.getX()), String.format("%.2f", p.getY()), String.format("%.2f", p.getZ()), drop.toShortString(), p.onGround(), hanging, p.isShiftKeyDown());
                dropTapTicks++;
                mc.options.keyShift.setDown(false);
                pressAlong(p, dx, dz);
            } else if (speed > 0.06 || tapped) {
                mc.options.keyShift.setDown(false); // still moving: let it slow down before stepping off
                releaseMoveKeys();
                noteDropTap(p);
            } else {
                // Step off a tap at a time: a held key carries half a block a tick across the
                // hole, a tap falls almost straight down.
                mc.options.keyShift.setDown(false);
                pressAlong(p, dx, dz);
                dropTapped = true;
                noteDropTap(p);
            }
            return;
        }
        // Steps up. If a jump taken now would land on the step, pre-jump from the run (preJump). Otherwise, for a step
        // of a block or more (stepTarget, from stepUpAhead), face it first while standing, then press W and jump
        // together. Pushing into the step while still turning made the stuck check jump and back off over and over.
        if (userJumpTick(p)) return; // replay a recorded move of the player's (not used in this mod)
        if (p.onGround() && forceJumpTicks == 0 && preJump(p)) {
            forceJumpTicks = 2;
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(p.isSprinting());
            mc.options.keyShift.setDown(false);
            turningInPlace = false;
            return;
        }
        if (stepTarget != null && backupTicks == 0) {
            // A step higher than a normal jump (with jump boost): jump from the middle of the cell the path climbs
            // from, since that's where the search checked the headroom. Only when there's no room to jump where you
            // are; otherwise run at it and jump early.
            if (stepFrom != null && p.onGround() && standTop(stepTarget) - p.getY() > 1.3 && !roomToJump(p)) {
                double fx = stepFrom.getX() + 0.5 - p.getX(), fz = stepFrom.getZ() + 0.5 - p.getZ();
                if (Math.hypot(fx, fz) > 0.35) {
                    finalKeys = false;
                    strafeSide = 0;
                    turningInPlace = true; // not stuck, just getting into position
                    mc.options.keySprint.setDown(false);
                    mc.options.keyShift.setDown(true); // crouched so it stops on the spot instead of overshooting
                    Approach.keys(mc, fx, fz, 0.2);
                    return;
                }
                mc.options.keyShift.setDown(false);
            }
            double sx = stepTarget.getX() + 0.5 - p.getX(), sz = stepTarget.getZ() + 0.5 - p.getZ();
            if (Math.hypot(sx, sz) < 0.7) { // right at it: press the keys toward it (view held, see updateLookPoint)
                finalKeys = false;
                strafeSide = 0;
                turningInPlace = false;
                mc.options.keySprint.setDown(false);
                mc.options.keyShift.setDown(false);
                pressAlong(p, sx, sz);
                if (p.onGround() && forceJumpTicks == 0 && roomToJump(p)) forceJumpTicks = 3;
                return;
            }
            float yawTo = (float) Math.toDegrees(Math.atan2(-sx, sz));
            float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(yawTo - p.getYRot()));
            finalKeys = false;
            strafeSide = 0;
            mc.options.keySprint.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyDown.setDown(false);
            if (p.onGround() && off > 25) {
                mc.options.keyUp.setDown(false);
                turningInPlace = true;
                return;
            }
            turningInPlace = false;
            mc.options.keyUp.setDown(true);
            // a high step with room above: sprint at it and jump early, about a block and a half from its middle
            boolean high = standTop(stepTarget) - p.getY() > 1.3;
            mc.options.keySprint.setDown(high && off < 15);
            if (p.onGround() && forceJumpTicks == 0 && Math.hypot(sx, sz) < (high ? 1.65 : 1.4) && roomToJump(p)) forceJumpTicks = 3;
            return;
        }
        // Not for a goal one step up (e.g. a raised slab): keys alone slid off
        // beside it, so let the walk jump up onto it.
        BlockPos near = nearestGoal(p);
        boolean stepUp = near != null && standTop(near) - p.getY() > 0.6;
        finalKeys = !stepUp && (rot.pos >= rot.lastIndex() - 3 || rot.complete) && flatToGoal(p, true) < FINAL_KEYS;
        if (finalKeys) { // onto it with the keys, the view held (turning toward a point this close spins the view)
            turningInPlace = false;
            mc.options.keySprint.setDown(false);
            // crouched so it doesn't run past the goal or off an edge
            if (sneakArrive && levelToEnd(p)) mc.options.keyShift.setDown(true);
            BlockPos g = nearestGoal(p);
            // goal a step or more down: slow to a crawl before going off the edge onto it, or it flies past
            Vec3 gv = p.getDeltaMovement();
            double gs = Math.hypot(gv.x, gv.z), gx = g.getX() + 0.5 - p.getX(), gz = g.getZ() + 0.5 - p.getZ();
            if (p.onGround() && p.getY() - standTop(g) > 0.6 && gs > 0.16) {
                Approach.release(mc);
                if (gs > 0.3 && gx * gv.x + gz * gv.z > 0) pressAlong(p, -gv.x, -gv.z); // brake
                return;
            }
            // the keys toward the goal aren't moving you (caught on a corner): use a key direction that gets around it
            double[] round = roundTo(p, gx, gz);
            if (round != null) {
                pressAlong(p, round[0], round[1]);
                return;
            }
            // allow for the slide, without walking back for a small overshoot
            Approach.keys(mc, gx, gz, 0.15);
            return;
        }
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyDown.setDown(false);
        // release forward a slide's length from the end so it stops there rather than past it
        double speed = Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z);
        double coast = p.onGround() ? speed * 1.2 : 0;
        double toGoal = flatToGoal(p, true);
        boolean nearEnd = rot.pos >= rot.lastIndex() - 3;
        boolean slide = toGoal < Math.max(0.5, coast) && nearEnd;
        // Walk the last few blocks, and turn on the spot when the goal is well off to the side. At a sprint V5's
        // turning can't make a tight turn onto the goal and circles it.
        boolean finalApproach = nearEnd && toGoal < FINAL_APPROACH;
        // crouch over the last bit when asked to (e.g. arriving on a narrow ledge)
        if (sneakArrive && finalApproach && toGoal < 2.5 && p.onGround() && levelToEnd(p)) mc.options.keyShift.setDown(true);
        boolean offToSide = false;
        if (finalApproach) {
            BlockPos g = nearestGoal(p);
            if (g != null) {
                float yawTo = (float) Math.toDegrees(Math.atan2(-(g.getX() + 0.5 - p.getX()), g.getZ() + 0.5 - p.getZ()));
                offToSide = Math.abs(net.minecraft.util.Mth.wrapDegrees(yawTo - p.getYRot())) > 60;
            }
        }
        // look direction far from where you face (path starts behind you, sharp turn): turn first, then walk,
        // rather than sprinting off the wrong way
        boolean offFar = Float.isNaN(rot.lookYaw) || Math.abs(net.minecraft.util.Mth.wrapDegrees(rot.lookYaw - p.getYRot())) > TURN_FIRST_DEG;
        turnFirstTicks = offFar ? turnFirstTicks + 1 : 0;
        // a look that swings off for one tick at a jog in the line isn't worth
        // stopping for; only after 3 ticks, or when slow
        boolean turnFirst = offFar && (Float.isNaN(rot.lookYaw) || turnFirstTicks >= 3 || speed < 0.25);
        // turn on the spot for at most 1.2 s, then walk on while turning (a jumpy
        // look point in a tunnel kept it turning)
        long nowTurn = System.currentTimeMillis();
        if (!turnFirst) turnFirstSince = 0;
        else if (turnFirstSince == 0) turnFirstSince = nowTurn;
        else if (nowTurn - turnFirstSince > 1200 && !Float.isNaN(rot.lookYaw)) turnFirst = false;
        boolean brake = wouldLeavePath(p);
        // A corner coming up at a run (the line turns 40°+ within the next 7 ticks): stop sprinting,
        // release W for a tick to end the sprint (the sprint key alone doesn't), and walk round it
        // rather than running wide into the wall.
        double[] corner = p.onGround() ? cornerAhead(p, speed) : null;
        boolean cornerSlow = corner != null && corner[0] < speed * 7 + 0.5;
        boolean cornerLift = cornerSlow && p.isSprinting() && corner[1] > 45 && speed > 0.4 && corner[0] < speed * 5 + 0.5;
        // goal a step or more down and close: don't run at it (see finalKeys)
        BlockPos endGoal = nearEnd ? nearestGoal(p) : null;
        boolean easeDown = endGoal != null && toGoal < FINAL_APPROACH && p.getY() - standTop(endGoal) > 0.6 && speed > 0.3;
        // dropping onto the path's next block: release W once over it and tap S if past it, or it lands too far
        int fall = fallOnto(p);
        mc.options.keyUp.setDown(!slide && !offToSide && !turnFirst && !brake && fall == 0 && !dropCoast && !cornerLift && !easeDown);
        if (fall < 0) mc.options.keyDown.setDown(true);
        noteNoForward(p, slide, offToSide, turnFirst, brake);
        mc.options.keySprint.setDown(!slide && !shiftWalk && !finalApproach && !turnFirst && stepTarget == null && !sharpTurnAhead(p) && !brake && !dropCoast
            && !cornerSlow);
        turningInPlace = offToSide || turnFirst;
        // When turning onto the way while walking, strafe toward it as you'd take a corner; with only W held it
        // swung wide and caught the inside corner. STRAFE_* keep it from flickering side to side. Ground only:
        // strafing in the air pushes jumps sideways.
        if (!slide && !offToSide && !turnFirst && !Float.isNaN(rot.lookYaw) && p.onGround()) {
            float off = net.minecraft.util.Mth.wrapDegrees(rot.lookYaw - p.getYRot());
            int want = off > STRAFE_INTO_TURN_DEG ? 1 : off < -STRAFE_INTO_TURN_DEG ? -1
                    : strafeSide != 0 && Math.abs(off) > STRAFE_LET_GO_DEG && Math.signum(off) == strafeSide ? strafeSide : 0;
            long nowMs = System.currentTimeMillis();
            if (want != strafeSide && want != 0 && strafeSide != 0 && nowMs - strafeSince < STRAFE_SWITCH_MS) want = 0;
            if (want != strafeSide) {
                strafeSide = want;
                strafeSince = nowMs;
            }
            mc.options.keyRight.setDown(strafeSide > 0);
            mc.options.keyLeft.setDown(strafeSide < 0);
        } else {
            strafeSide = 0;
        }
        if (turnFirst && !offToSide && !slide && !Float.isNaN(rot.lookYaw)) {
            // turning onto the way: meanwhile move along it with the keys, as you'd strafe while turning, instead of
            // standing still or walking into whatever is in front
            double way = Math.toRadians(rot.lookYaw), yaw = Math.toRadians(p.getYRot());
            double dx = -Math.sin(way), dz = Math.cos(way);
            // ...toward the path a block ahead, not the look point round the bend, which
            // made it cut the corner into a low ceiling
            Vec3 onWay = rot.at(rot.pos + 1.0);
            if (onWay != null && Math.hypot(onWay.x - p.getX(), onWay.z - p.getZ()) > 0.3) {
                double nl = Math.hypot(onWay.x - p.getX(), onWay.z - p.getZ());
                dx = (onWay.x - p.getX()) / nl;
                dz = (onWay.z - p.getZ()) / nl;
            }
            double fwd = dx * -Math.sin(yaw) + dz * Math.cos(yaw), right = dx * -Math.cos(yaw) + dz * -Math.sin(yaw);
            mc.options.keyUp.setDown(fwd > 0.38);
            mc.options.keyDown.setDown(fwd < -0.38);
            mc.options.keyRight.setDown(right > 0.38);
            mc.options.keyLeft.setDown(right < -0.38);
        }
    }

    /**
     * True if the rest of the path stays on your level. Only then is the end approached crouched, since crouching stops
     * you walking off a ledge the path continues down.
     */
    private static boolean levelToEnd(LocalPlayer p) {
        if (path == null) return true;
        int at = nodeAt(p);
        for (int i = Math.max(0, at); i < path.size(); i++) if (Math.abs(standTop(path.get(i)) - p.getY()) > 0.6) return false;
        return true;
    }

    /** Recent step jumps made from about the same spot: where, since when, and how many. */
    private static Vec3 stepJumpsFrom;
    private static long stepJumpsSince;
    private static int stepJumps;

    /** Stopped on purpose to turn; not stuck, so the stuck check shouldn't jump. */
    private static boolean turningInPlace;
    private static int turnInPlaceTicks;
    /** Ticks in a row spent walking into a wall. */
    private static int bumpTicks;
    private static long lastRdbtJump;
    private static int stoppedTicks;

    /** When W was released mid-walk, and why (bit flags), for the log. */
    private static long noForwardSince;
    private static int noForwardWhy;

    /** Once W is pressed again, logs how long it was released mid-walk and why (for diagnosing stop-start walking). */
    private static void noteNoForward(LocalPlayer p, boolean slide, boolean offToSide, boolean turnFirst, boolean brake) {
        long now = System.currentTimeMillis();
        if (!mc.options.keyUp.isDown()) {
            if (noForwardSince == 0) {
                noForwardSince = now;
                noForwardWhy = 0;
            }
            noForwardWhy |= (slide ? 1 : 0) | (offToSide ? 2 : 0) | (turnFirst ? 4 : 0) | (brake ? 8 : 0);
            return;
        }
        if (noForwardSince != 0 && now - noForwardSince > 400) {
            List<String> why = new ArrayList<>();
            if ((noForwardWhy & 1) != 0) why.add("sliding to the end");
            if ((noForwardWhy & 2) != 0) why.add("the goal off to the side");
            if ((noForwardWhy & 4) != 0) why.add("turning first");
            if ((noForwardWhy & 8) != 0) why.add("coasting into a turn");
            say("W let go of for {} ms at {}: {}", now - noForwardSince, p.blockPosition().toShortString(), why.isEmpty() ? "?" : String.join(", ", why));
        }
        noForwardSince = 0;
    }

    /** True if the path climbs more than a step within the next few nodes and within 2.5 blocks of you. */
    private static boolean pathGoesUp(LocalPlayer p) {
        if (path == null) return false;
        int at = Math.max(0, jumps.lastNearestIndex);
        for (int i = at; i < Math.min(path.size(), at + 4); i++) {
            BlockPos n = path.get(i);
            if (Math.hypot(n.getX() + 0.5 - p.getX(), n.getZ() + 0.5 - p.getZ()) > 2.5) continue;
            if (jumps.supportTop(n) - p.getY() > Jumps.STEP_HEIGHT && entryRise(p, n) > Jumps.STEP_HEIGHT) return true;
        }
        return false;
    }

    /**
     * True if a jump taken now (W toward {@code toward}, or a few nodes ahead) lands on the path: within 1.4
     * horizontally of a node from about here on, at its height (±0.6), within 2 s. With a strong jump boost even
     * a small hop can fly off a narrow walkway.
     */
    private static boolean jumpLandsOnWay(LocalPlayer p, BlockPos toward) {
        if (path == null || path.isEmpty()) return false;
        int at = Math.max(0, nodeAt(p));
        BlockPos aimAt = toward != null ? toward : path.get(Math.min(path.size() - 1, at + 3));
        List<Vec3> trace = new ArrayList<>();
        Vec3 v = p.getDeltaMovement();
        crossing(p.position(), v.x, v.z, p.isSprinting(), p.getYRot(), new Vec3(aimAt.getX() + 0.5, aimAt.getY(), aimAt.getZ() + 0.5),
            Double.MAX_VALUE, trace);
        if (trace.size() < 3 || trace.size() >= 41) return false; // didn't land within 2 s
        Vec3 land = trace.get(trace.size() - 1);
        for (int k = Math.max(0, at - 1); k < Math.min(path.size(), at + 16); k++) {
            BlockPos n = path.get(k);
            if (Math.hypot(n.getX() + 0.5 - land.x, n.getZ() + 0.5 - land.z) <= 1.4 && Math.abs(standTop(n) - land.y) <= 0.6) return true;
        }
        return false;
    }

    /**
     * True if in the next two ticks you'd run into something taller than a step (not a slab or a stair's low half) but
     * low enough to jump onto (up to 1.25), with headroom to jump.
     */
    private static boolean smallStepAhead(LocalPlayer p) {
        Vec3 v = p.getDeltaMovement();
        if (Math.hypot(v.x, v.z) < 0.15) return false;
        net.minecraft.world.phys.AABB box = p.getBoundingBox();
        for (int ticks = 1; ticks <= 2; ticks++) {
            net.minecraft.world.phys.AABB ahead = box.move(v.x * ticks, 0, v.z * ticks);
            if (mc.level.noCollision(p, ahead)) continue;
            if (mc.level.noCollision(p, ahead.move(0, Jumps.STEP_HEIGHT, 0))) return false; // steps up onto it
            if (!mc.level.noCollision(p, ahead.move(0, 1.26, 0))) return false; // a wall
            return mc.level.noCollision(p, box.move(0, 1.3, 0));
        }
        return false;
    }

    /**
     * True if there's headroom for a jump: as much as the nearby step up needs, otherwise half a block. With a block
     * right above, every kind of jump just hopped in place.
     */
    private static boolean roomToJump(LocalPlayer p) {
        double need = 0.5;
        if (path != null) {
            int at = Math.max(0, jumps.lastNearestIndex);
            for (int i = at; i < Math.min(path.size(), at + 3); i++) {
                BlockPos n = path.get(i);
                if (Math.hypot(n.getX() + 0.5 - p.getX(), n.getZ() + 0.5 - p.getZ()) > 2.0) continue;
                need = Math.max(need, Math.min(V5Search.maxClimb + 0.2, jumps.supportTop(n) - p.getY()));
            }
        }
        return mc.level.noCollision(p, p.getBoundingBox().expandTowards(0, need + 0.05, 0));
    }

    /** Cancels a jump that's about to happen when there's no headroom for it. */
    private static void checkJumpRoom(LocalPlayer p) {
        if (!mc.options.keyJump.isDown() || !p.onGround() || p.isInWater() || p.isInLava()) return;
        if (userJump != null) return; // replaying a recorded move: jump exactly as recorded
        if (roomToJump(p)) return;
        mc.options.keyJump.setDown(false);
        forceJumpTicks = 0;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastNoRoomLog > 1000) {
            lastNoRoomLog = nowMs;
            say("no room over your head to jump at {}: not jumped", p.blockPosition().toShortString());
        }
    }

    private static long lastNoRoomLog;

    private static boolean againstFullBlock(LocalPlayer p) {
        Level level = mc.level;
        net.minecraft.world.phys.AABB box = p.getBoundingBox();
        int bx = (int) Math.floor(p.getX()), by = (int) Math.floor(p.getY()), bz = (int) Math.floor(p.getZ());
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 1; y++) {
                    BlockPos b = new BlockPos(bx + x, by + y, bz + z);
                    if (!level.getBlockState(b).isCollisionShapeFullBlock(level, b)) continue;
                    if (new net.minecraft.world.phys.AABB(b.getX() - 0.01, b.getY(), b.getZ() - 0.01,
                            b.getX() + 1.01, b.getY() + 1, b.getZ() + 1.01).intersects(box)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Jumps when walking into a block a step won't climb, with room above it. The path's own jumps only know the blocks
     * under its nodes, so off them (round a corner, on the final approach) it could stand against a one-block step, and
     * the stuck check never fired because sliding along the wall counted as moving.
     */
    private static void bumpJump(LocalPlayer p) {
        boolean pushing = mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown()
                || mc.options.keyRight.isDown();
        long nowMs = System.currentTimeMillis();
        // about to run into a small step the path goes up: jump now at full speed instead of stopping against it first
        if (pushing && p.onGround() && forceJumpTicks == 0 && !p.isInWater() && pathGoesUp(p) && smallStepAhead(p)
                && jumpLandsOnWay(p, null)) {
            forceJumpTicks = 2;
            bumpTicks = 0;
            return;
        }
        boolean stopped = pushing && p.onGround() && Math.abs(p.getDeltaMovement().x) + Math.abs(p.getDeltaMovement().z) < 0.02;
        stoppedTicks = stopped ? stoppedTicks + 1 : 0;
        // the jumps below only apply where the path goes up; elsewhere it jumped onto blocks the path goes around
        if (!pathGoesUp(p)) {
            bumpTicks = 0;
            return;
        }
        // Based on RDBT's MovementHelper: pushing but almost stopped against a full block at feet or
        // head height, at most once every half second (trying costs nothing). Waiting 5 ticks stops it
        // jumping as a walk sets off next to a wall.
        if (stopped && stoppedTicks >= 5 && p.horizontalCollision && !p.isInWater() && !p.isInLava()
                && nowMs - lastRdbtJump >= 500 && againstFullBlock(p)) {
            say("stopped against a block at {}: jumping (RDBT's)", p.blockPosition().toShortString());
            lastRdbtJump = nowMs;
            forceJumpTicks = 3;
            bumpTicks = 0;
            return;
        }
        if (!pushing || !p.onGround() || !p.horizontalCollision || p.isInWater() || p.isInLava()) {
            bumpTicks = 0;
            return;
        }
        if (++bumpTicks < 3) return;
        // the direction the keys push in, in world space
        double f = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double r = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
        double yaw = Math.toRadians(p.getYRot());
        double dx = f * -Math.sin(yaw) - r * Math.cos(yaw), dz = f * Math.cos(yaw) - r * Math.sin(yaw);
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return;
        dx /= len;
        dz /= len;
        Level level = mc.level;
        int feet = (int) Math.floor(p.getY() + 0.001);
        BlockPos head = BlockPos.containing(p.getX(), feet + 2, p.getZ());
        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) return; // no room to jump
        for (double ahead : new double[]{0.6, 0.9}) {
            BlockPos at = BlockPos.containing(p.getX() + dx * ahead, feet, p.getZ() + dz * ahead);
            var shape = level.getBlockState(at).getCollisionShape(level, at);
            if (shape.isEmpty()) continue;
            double top = at.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
            if (top - p.getY() <= 0.6 || top - p.getY() > 1.25) return; // stepped up, or too high to jump
            if (entryRise(p, at.above()) <= Jumps.STEP_HEIGHT) return; // a stair's low side faces you: walk up it
            BlockPos above = at.above(), above2 = at.above(2);
            if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()
                    || !level.getBlockState(above2).getCollisionShape(level, above2).isEmpty()) return;
            say("walking into a step at {}: jumping", at.toShortString());
            forceJumpTicks = 3;
            bumpTicks = 0;
            // jumping at steps again and again without getting anywhere (e.g. in a pit): search for another way
            if (stepJumpsFrom == null || p.position().distanceTo(stepJumpsFrom) > 1.5 || nowMs - stepJumpsSince > 3000) {
                stepJumpsFrom = p.position();
                stepJumpsSince = nowMs;
                stepJumps = 0;
            }
            if (++stepJumps >= 4) {
                stepJumpsFrom = null;
                recalculate("jumping at a step and getting nowhere");
            }
            return;
        }
    }

    /**
     * True when moving faster than a sprint (e.g. with speed effects) and the way to go is more than 50° off the
     * direction of travel: W is released to coast until you're heading its way. A tighter test made the walk stop and
     * start at every small bend of the smoothed line.
     */
    private static boolean wouldLeavePath(LocalPlayer p) {
        if (!p.onGround() || Float.isNaN(rot.lookYaw)) return false;
        net.minecraft.world.phys.Vec3 v = p.getDeltaMovement();
        if (Math.hypot(v.x, v.z) < 0.3) return false; // walking or sprinting: normal turning keeps it on the path
        float going = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
        return Math.abs(net.minecraft.util.Mth.wrapDegrees(rot.lookYaw - going)) > 50;
    }

    /** True if the path turns more than 60° within the next few blocks (don't sprint into it and overshoot). */
    private static boolean sharpTurnAhead(LocalPlayer p) {
        // measured on the walked line, not the search's nodes, whose one-block jogs on a diagonal look like sharp turns
        if (rot.pts == null || rot.pts.size() < 3) return false;
        Vec3 a = rot.at(rot.pos), b = rot.at(rot.pos + 1.0);
        double ax = b.x - a.x, az = b.z - a.z, la = Math.hypot(ax, az);
        if (la < 0.3) return false;
        for (double t = rot.pos + 1.5; t <= Math.min(rot.pos + 3.5, rot.lastIndex() - 1.0); t += 0.5) {
            Vec3 c = rot.at(t), d = rot.at(t + 1.0);
            double bx = d.x - c.x, bz = d.z - c.z, lb = Math.hypot(bx, bz);
            if (lb < 0.3) continue;
            if ((ax * bx + az * bz) / (la * lb) < 0.5 && Math.hypot(c.x - p.getX(), c.z - p.getZ()) < 3.0) return true;
        }
        return false;
    }

    /** The drop being taken: set at its edge on the ground, kept through the fall. */
    private static BlockPos dropTarget;
    /** A drop that's being walked off (or slid off, see {@link #dropWalkedCoast}); kept until it's taken. */
    private static BlockPos dropWalked;
    private static boolean dropWalkedCoast;
    /** A drop where sliding off stopped short of the edge; it's walked or crept off instead. */
    private static BlockPos dropNoCoast;
    /** A drop that has to land on one exact spot, so it's crept off; kept until it's taken. */
    private static BlockPos dropCareful;
    /** Sliding off a drop: W (and with it sprint) is released before the edge this tick. */
    private static boolean dropCoast;
    /** The last drop logged, so each one is logged once. */
    private static BlockPos dropLogged;

    /**
     * Decides how to take the drop to node {@code k}: walk off at the current pace ({@link #dropLands}), or
     * release W now and slide off more slowly ({@link #dropCoast}), whichever lands on the path. If neither does,
     * the landing spot is exact (e.g. a ladder or ledge on the way down), so it's crept off
     * ({@link #dropCareful}) and this returns false.
     */
    private static boolean plainDrop(LocalPlayer p, int at, int k) {
        BlockPos n = path.get(k);
        String how;
        if (dropLands(p, at, k, true)) how = "walked off";
        else if (!n.equals(dropNoCoast) && dropLands(p, at, k, false)) {
            dropCoast = true;
            how = "slid off (W let go of before the edge)";
        } else {
            dropCareful = n;
            how = "crept off crouched (walked or slid off, it'd come down off the way)";
        }
        if (!n.equals(dropLogged)) {
            dropLogged = n;
            say("drop of {} to {}: {}", String.format("%.1f", p.getY() - standTop(n)), n.toShortString(), how);
        }
        return dropCareful == null;
    }

    /**
     * Simulates walking off the drop to node {@code k} through the real blocks (speed, ground friction, air drag,
     * gravity) the way the walker would take it: on the ground W toward the next nodes with {@code walk}, or a slide
     * with nothing pressed without it; in the air W toward the drop's column, then nothing ({@link #fallOnto}). True if
     * it lands on the path (the drop's node or one of the next 8, within about a block), touches nothing climbable on
     * the way down, and doesn't stop short of the edge.
     */
    private static boolean dropLands(LocalPlayer p, int at, int k, boolean walk) {
        BlockPos target = path.get(k);
        boolean sprint = walk && p.isSprinting(); // without the forward key the sprint stops
        double attr = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (p.isSprinting() && !sprint) attr /= 1.3;
        double w = p.getBbWidth() / 2, h = p.getBbHeight();
        Vec3 pos = p.position(), v = p.getDeltaMovement();
        double vx = v.x, vy = v.y, vz = v.z, top = p.getY();
        boolean ground = true;
        int wp = at + 1;
        for (int t = 0; t < 80; t++) {
            double ax = 0, az = 0;
            float fr = mc.level.getBlockState(BlockPos.containing(pos.x, pos.y - 0.500001, pos.z)).getBlock().getFriction();
            if (ground) {
                if (walk) {
                    while (wp < k && Math.hypot(path.get(wp).getX() + 0.5 - pos.x, path.get(wp).getZ() + 0.5 - pos.z) < 0.6) wp++;
                    BlockPos n = path.get(wp);
                    double dx = n.getX() + 0.5 - pos.x, dz = n.getZ() + 0.5 - pos.z, len = Math.hypot(dx, dz);
                    double a = attr * (0.21600002 / (fr * fr * fr));
                    if (len > 1e-6) {
                        ax = a * dx / len;
                        az = a * dz / len;
                    }
                }
            } else {
                double dx = target.getX() + 0.5 - pos.x, dz = target.getZ() + 0.5 - pos.z, d = Math.hypot(dx, dz), sp = Math.hypot(vx, vz);
                if ((vy > -0.1 || d > 0.6 + sp * 3) && d > 1e-6) { // W: not falling yet, or still short of the column
                    double a = sprint ? 0.026 : 0.02;
                    ax = a * dx / d;
                    az = a * dz / d;
                } else if (sp > 0.05 && dx * vx + dz * vz < 0 && d > 0.35) { // past it: pull back a touch
                    ax = -0.02 * vx / sp;
                    az = -0.02 * vz / sp;
                }
            }
            vx += ax;
            vz += az;
            var box = new net.minecraft.world.phys.AABB(pos.x - w, pos.y, pos.z - w, pos.x + w, pos.y + h, pos.z + w);
            Vec3 moved = net.minecraft.world.entity.Entity.collideBoundingBox(p, new Vec3(vx, vy, vz), box, mc.level, List.of());
            pos = pos.add(moved);
            if (mc.level.getBlockState(BlockPos.containing(pos)).is(net.minecraft.tags.BlockTags.CLIMBABLE)) return false;
            boolean wasGround = ground, hitY = Math.abs(moved.y - vy) > 1e-7;
            ground = hitY && vy < 0;
            if (Math.abs(moved.x - vx) > 1e-7) vx = 0;
            if (Math.abs(moved.z - vz) > 1e-7) vz = 0;
            if (hitY) vy = 0;
            if (ground && pos.y < top - 1.0) {
                // landed: at or past the drop's node means it works; on a node before it (a
                // step down first), carry on from there
                for (int i = at + 1; i < Math.min(path.size(), k + 9); i++) {
                    BlockPos n = path.get(i);
                    if (Math.abs(standTop(n) - pos.y) < 0.6 && Math.hypot(n.getX() + 0.5 - pos.x, n.getZ() + 0.5 - pos.z) <= 1.3) {
                        if (i >= k) return true;
                        top = pos.y;
                        break;
                    }
                }
                if (top != pos.y) {
                    // landing on the path's level a few blocks off it, with a straight
                    // walk back, is as good as landing on it
                    for (int i = k; i < Math.min(path.size(), k + 9); i++) {
                        BlockPos n = path.get(i);
                        Vec3 c = new Vec3(n.getX() + 0.5, standTop(n), n.getZ() + 0.5);
                        if (Math.abs(c.y - pos.y) < 0.3 && Math.hypot(c.x - pos.x, c.z - pos.z) <= 5
                                && floorAlong(pos, c, pos.y, 0.3)) return true;
                    }
                    return false;
                }
            }
            if (ground && Math.hypot(vx, vz) < 0.003) return false; // stopped short of the edge, or against a wall
            double drag = wasGround ? fr * 0.91 : 0.91;
            vx *= drag;
            vz *= drag;
            vy = (vy - 0.08) * 0.98;
        }
        return false;
    }
    /** Stepping off a drop: whether a key was tapped last tick, and ticks crept at the edge without getting closer. */
    private static boolean dropTapped;
    private static int dropCreepStill;
    private static double dropLastDist = Double.MAX_VALUE;

    private static Vec3 dropTapFrom;
    private static int dropTapTicks;

    /** Counts ticks spent tapping toward a drop without moving. */
    private static void noteDropTap(LocalPlayer p) {
        Vec3 pos = p.position();
        if (dropTapFrom == null || pos.distanceTo(dropTapFrom) > 0.05) {
            dropTapFrom = pos;
            dropTapTicks = 0;
        } else {
            dropTapTicks++;
        }
    }

    // ── replaying the player's recorded long jumps (V5Search.likedEdges); not used in this mod, kept from the Java mod ──

    private static double[] userJump;
    private static long userJumpLogged;
    private static long userJumpSince;
    private static boolean userJumpAir;
    private static int userJumpAirTicks;
    /** Where the view is held while the jump is taken (see updateLookPoint). */
    private static Vec3 userJumpLook;

    /** The next climb on the path that's one of the player's recorded long jumps, take-off close by; or null. */
    private static double[] userJumpAhead(LocalPlayer p) {
        if (path == null || V5Search.likedEdges.isEmpty() || withoutUsersMoves) return null;
        int at = nodeAt(p);
        if (at < 0) return null;
        for (int k = Math.max(1, at); k < Math.min(path.size(), at + 6); k++) {
            BlockPos a = path.get(k - 1), b = path.get(k);
            if (Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()) < 1.6) continue;
            boolean up = standTop(b) - standTop(a) >= 1.0;
            for (double[] e : V5Search.likedEdges)
                if ((int) e[3] == b.getX() && (int) e[4] == b.getY() && (int) e[5] == b.getZ() && (int) e[1] == a.getY()
                        && Math.abs((int) e[0] - a.getX()) <= 1 && Math.abs((int) e[2] - a.getZ()) <= 1
                        && !V5Search.failedEdges.contains(V5Search.edgeKey(e))) {
                    boolean played = e.length > 17 && e[16] > 0; // recorded tick by tick: replay it as recorded
                    if (!up && !played) return null; // a long drop with no recording: just walk off it
                    double fx = played ? e[12] : e[6], fz = played ? e[14] : e[8];
                    return Math.hypot(fx - p.getX(), fz - p.getZ()) <= 8 ? e : null;
                }
            return null;
        }
        return null;
    }

    /**
     * Takes a recorded long jump the way the player did: get onto their line behind the take-off, run along it, jump
     * where they jumped and hold W until landing. Returns true when this tick's keys have been set.
     */
    private static boolean userJumpTick(LocalPlayer p) {
        long now = System.currentTimeMillis();
        double[] e = userJump;
        if (e == null) {
            if (!p.onGround() || forceJumpTicks > 0) return false;
            e = userJumpAhead(p);
            if (e == null) return false;
            userJump = e;
            userJumpAir = false;
            long key = V5Search.edgeKey(e);
            if (key == userJumpLogged) return userJumpTick(p); // same jump as before: log it only once
            userJumpLogged = key;
            userJumpSince = now;
            say("your jump on the way: from {} {} {} to {} {} {}", String.format(java.util.Locale.US, "%.1f", e[6]),
                String.format(java.util.Locale.US, "%.1f", e[7]), String.format(java.util.Locale.US, "%.1f", e[8]),
                String.format(java.util.Locale.US, "%.1f", e[9]), String.format(java.util.Locale.US, "%.1f", e[10]),
                String.format(java.util.Locale.US, "%.1f", e[11]));
        }
        double tx = e[6], tz = e[8], lx = e[9], ly = e[10], lz = e[11];
        double ux = lx - tx, uz = lz - tz, ul = Math.hypot(ux, uz);
        finalKeys = false;
        strafeSide = 0;
        if (ul < 1e-3 || now - userJumpSince > 10000) {
            if (now - userJumpSince > 10000) say("your jump on the way: not taken in 10 s, on as before");
            endUserJump();
            return false;
        }
        if (e.length > 17 && e[16] > 0) return replayTick(p, e);
        mc.options.keyShift.setDown(false);
        ux /= ul;
        uz /= ul;
        if (userJumpAir) { // in the air: W and sprint toward their landing spot until down
            userJumpAirTicks++;
            userJumpLook = new Vec3(lx, p.getEyeY(), lz);
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            if (p.onGround() && userJumpAirTicks > 3) {
                say("your jump on the way: down at {} {} {} ({} from where you came down)", String.format(java.util.Locale.US, "%.1f", p.getX()),
                    String.format(java.util.Locale.US, "%.1f", p.getY()), String.format(java.util.Locale.US, "%.1f", p.getZ()),
                    String.format(java.util.Locale.US, "%.1f", p.position().distanceTo(new Vec3(lx, ly, lz))));
                userJump = null;
                userJumpLook = null;
                releaseMoveKeys();
                return false;
            }
            return true;
        }
        if (p.onClimbable() || p.getY() < e[7] - 1.5) { // below their take-off: search again from here
            endUserJump();
            recalculate("past your jump's take-off");
            return true;
        }
        double px = p.getX() - tx, pz = p.getZ() - tz;
        double along = px * ux + pz * uz, across = Math.abs(-px * uz + pz * ux);
        Vec3 v = p.getDeltaMovement();
        double sp = Math.hypot(v.x, v.z);
        // on their line behind the take-off: run at it; otherwise go to where the run starts
        if (across > 0.45 || along > 0.15 || along < -4.0) {
            double back = runBack(p, e[6], e[7], e[8], ux, uz);
            double rx = tx - ux * back - p.getX(), rz = tz - uz * back - p.getZ();
            userJumpLook = new Vec3(tx - ux * back, p.getEyeY(), tz - uz * back);
            turningInPlace = true;
            mc.options.keySprint.setDown(false);
            if (Math.hypot(rx, rz) > 0.3) pressAlong(p, rx, rz);
            else releaseMoveKeys();
            return true;
        }
        userJumpLook = new Vec3(lx, p.getEyeY() + 0.5, lz);
        float yawTo = (float) Math.toDegrees(Math.atan2(-ux, uz));
        float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(yawTo - p.getYRot()));
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyDown.setDown(false);
        if (off > 20 && sp < 0.15) { // stand and face along the line first
            mc.options.keyUp.setDown(false);
            turningInPlace = true;
            return true;
        }
        turningInPlace = false;
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(true);
        if (along >= -Math.max(0.25, sp) && p.onGround()) { // at their take-off (the jump key is read next tick)
            forceJumpTicks = 2;
            userJumpAir = true;
            userJumpAirTicks = 0;
        }
        return true;
    }

    /** The tick of the recorded move being played, or -1 while getting to where it starts. */
    private static int replayAt = -1;

    private static void endUserJump() {
        userJump = null;
        userJumpLogged = 0;
        userJumpLook = null;
        replayAt = -1;
        replayHeading = false;
        releaseMoveKeys();
        mc.options.keyJump.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyShift.setDown(false);
    }

    /** Offset in the edge array of tick {@code i} of the move's track, stored as {yaw, pitch, keys, x, z}. */
    private static int at(int i) {
        return 17 + REPLAY_STRIDE * i;
    }

    private static final int REPLAY_STRIDE = 5;

    /**
     * Plays a recorded move back as the player made it: walk to where their run began, then press their keys
     * and follow their look tick by tick until landing. For moves no jump simulation gets right, like jumping
     * into a wall and climbing it with W held.
     */
    private static boolean replayTick(LocalPlayer p, double[] e) {
        int n = (int) e[16], toff = (int) e[15];
        // a way down is played from the take-off, not from the run up to it
        boolean up = e[10] > e[7];
        Vec3 v = p.getDeltaMovement();
        double sp = Math.hypot(v.x, v.z);
        if (replayAt < 0) {
            // dropped below their take-off on the way to it, or onto a ladder: search
            // again from here instead of climbing back
            if (p.onClimbable() || p.getY() < e[7] - 1.5) {
                say("your move on the way: came down below its take-off at {} on the way to it, searched again from here",
                    String.format(java.util.Locale.US, "%.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()));
                endUserJump();
                recalculate("past your move's take-off");
                return true;
            }
            if (!p.onGround()) return true;
            mc.options.keyJump.setDown(false);
            mc.options.keyAttack.setDown(false);
            mc.options.keyShift.setDown(false);
            // where you join their run (its nearest point up to the take-off; for a way down, the take-off itself)
            int j = up ? nearestOnRun(p, e, 0, toff) : toff;
            double jx = e[at(j) + 3], jz = e[at(j) + 4], d = Math.hypot(jx - p.getX(), jz - p.getZ());
            if (d > (up ? 1.0 : 0.45)) { // join it a little ahead, never back at its start (that went back and forth)
                int k = Math.min(toff, j + (up ? 2 : 0));
                double kx = e[at(k) + 3], kz = e[at(k) + 4];
                // Head straight there only when close and nothing's in the way; further off, keep walking the path at a
                // sprint, or it arrives too slowly and the jump falls short.
                boolean close = d <= (replayHeading ? 4.0 : 2.5);
                if (!close || !straightClear(p, kx, kz)) {
                    replayHeading = false;
                    userJump = null;
                    userJumpLook = null;
                    return false;
                }
                replayHeading = true;
                userJumpLook = new Vec3(kx, p.getEyeY(), kz);
                turningInPlace = true;
                mc.options.keySprint.setDown(false);
                // a way down's take-off is at the edge: slide up to it rather than running past and dropping early
                if (!up && sp > 0.18 && d < 1.5) releaseMoveKeys();
                else pressAlong(p, kx - p.getX(), kz - p.getZ());
                return true;
            }
            replayHeading = false;
            replayAt = j;
            replayRunTicks = 0;
            replayAirTicks = 0;
            replayEdgeTicks = 0;
            say("your move on the way: played back from {} {} {} ({} of its {} ticks, the jump {} in)", String.format(java.util.Locale.US, "%.1f", p.getX()),
                String.format(java.util.Locale.US, "%.1f", p.getY()), String.format(java.util.Locale.US, "%.1f", p.getZ()), n - j, n, toff - j);
        }
        int i = replayAt;
        // The run-up: play their keys and look for the point you're at on their run (not by tick count, since joining
        // partway you're slower than they were) and jump where they jumped.
        if (i <= toff) {
            int k = Math.min(toff, nearestOnRun(p, e, i, toff));
            double tyaw = Math.toRadians(e[at(toff)]);
            double ux = -Math.sin(tyaw), uz = Math.cos(tyaw);
            double along = (p.getX() - e[6]) * ux + (p.getZ() - e[8]) * uz;
            boolean atIt = along >= -Math.max(0.25, sp) || Math.hypot(p.getX() - e[6], p.getZ() - e[8]) <= Math.max(0.3, sp);
            if (!atIt) {
                playKeys(p, e, Math.min(k, Math.max(0, toff - 1)), false);
                replayAt = Math.min(k, toff - 1);
                if (++replayRunTicks > 60) { // not at the take-off within 3 s: give up on this move
                    replayFailed(e, "not at your take-off in 3 s");
                    return false;
                }
                return true;
            }
            // the take-off: jump if they jumped (a walk-off is just walked off)
            replayJumped = up || ((int) e[at(toff) + 2] & 16) != 0 || toff + 1 < n && ((int) e[at(toff + 1) + 2] & 16) != 0;
            playKeys(p, e, toff, replayJumped);
            replayAt = toff + 1;
            replayAirTicks = 0;
            return true;
        }
        if (!p.onGround()) replayAirTicks++;
        if (replayAirTicks == 0 && p.onGround()) {
            // not off the ground yet: hold the take-off's keys until you are, so their flight starts when yours does
            if (++replayEdgeTicks > 30) {
                replayFailed(e, "never off the ground at your take-off");
                return false;
            }
            playKeys(p, e, toff, replayJumped);
            return true;
        }
        boolean down = replayAirTicks > 2 && (p.onGround() || p.isInWater() || p.onClimbable());
        // their ticks ran out while you're still in the air: hold their last keys until landing
        if (down || i >= n + 60) {
            double off = p.position().distanceTo(new Vec3(e[9], e[10], e[11]));
            // landing higher than they did isn't a miss: carry on from there
            boolean missed = off > 4.0 || off > 2.0 && (up ? p.getY() < e[10] - 0.5 : Math.abs(p.getY() - e[10]) > 1.0);
            say("your move on the way: done at {} {} {} ({} from where you came down)", String.format(java.util.Locale.US, "%.1f", p.getX()),
                String.format(java.util.Locale.US, "%.1f", p.getY()), String.format(java.util.Locale.US, "%.1f", p.getZ()),
                String.format(java.util.Locale.US, "%.1f", off));
            if (missed) {
                replayFailed(e, String.format(java.util.Locale.US, "came down %.1f off", off));
                return false;
            }
            endUserJump();
            // landed where they did and that's where the walk was going: done (walking on to
            // the block's centre went off the ledge)
            if (path != null && !path.isEmpty()) {
                BlockPos last = path.get(path.size() - 1);
                if (Math.hypot(last.getX() + 0.5 - p.getX(), last.getZ() + 0.5 - p.getZ()) < 2.0 && Math.abs(standTop(last) - p.getY()) < 0.8) {
                    finish(true, null);
                    return true;
                }
            }
            return false;
        }
        playKeys(p, e, Math.min(i, n - 1), false);
        replayAt++;
        return true;
    }

    /** True if a straight walk from here to ({@code x}, {@code z}) at your height is clear for the whole body. */
    private static boolean straightClear(LocalPlayer p, double x, double z) {
        double dx = x - p.getX(), dz = z - p.getZ(), len = Math.hypot(dx, dz);
        if (len < 0.2) return true;
        net.minecraft.world.phys.AABB box = p.getBoundingBox().move(0, 0.05, 0);
        int steps = (int) Math.ceil(len / 0.25);
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            if (!mc.level.noCollision(p, box.move(dx * f, 0, dz * f))) return false;
        }
        return true;
    }

    /** The tick of their run (from {@code from} to {@code to}) whose position is nearest where you are. */
    private static int nearestOnRun(LocalPlayer p, double[] e, int from, int to) {
        int best = from;
        double bd = Double.MAX_VALUE;
        for (int i = Math.max(0, from); i <= to; i++) {
            double d = Math.hypot(e[at(i) + 3] - p.getX(), e[at(i) + 4] - p.getZ());
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private static int replayRunTicks, replayAirTicks, replayEdgeTicks;
    /** Whether the move being played jumps at the take-off, and whether it's heading straight onto the run. */
    private static boolean replayJumped, replayHeading;

    /** Presses the recorded keys and sets the look for tick {@code i} of the move ({@code jump}: jump too). */
    private static void playKeys(LocalPlayer p, double[] e, int i, boolean jump) {
        int bits = (int) e[at(i) + 2];
        userJumpLook = lookAlong(p, (float) e[at(i)], (float) e[at(i) + 1]);
        turningInPlace = false;
        mc.options.keyShift.setDown(false);
        // press their keys in the world directions they pushed, relative to where you
        // face now (the view may still be turning)
        double f = ((bits & 1) != 0 ? 1 : 0) - ((bits & 2) != 0 ? 1 : 0), r = ((bits & 8) != 0 ? 1 : 0) - ((bits & 4) != 0 ? 1 : 0);
        double uy = Math.toRadians(e[at(i)]), my = Math.toRadians(p.getYRot());
        double wx = f * -Math.sin(uy) - r * Math.cos(uy), wz = f * Math.cos(uy) - r * Math.sin(uy);
        double fwd = wx * -Math.sin(my) + wz * Math.cos(my), right = wx * -Math.cos(my) + wz * -Math.sin(my), len = Math.hypot(fwd, right);
        mc.options.keyUp.setDown(len > 1e-6 && fwd > 0.38 * len);
        mc.options.keyDown.setDown(len > 1e-6 && fwd < -0.38 * len);
        mc.options.keyLeft.setDown(len > 1e-6 && right < -0.38 * len);
        mc.options.keyRight.setDown(len > 1e-6 && right > 0.38 * len);
        mc.options.keyJump.setDown(jump || (bits & 16) != 0);
        mc.options.keySprint.setDown((bits & 32) != 0);
        mc.options.keyAttack.setDown((bits & 64) != 0);
    }

    /** A recorded move that didn't work: excluded from future searches, and the walk searched again without it. */
    private static void replayFailed(double[] e, String why) {
        say("your move on the way: {} — left out from here on", why);
        V5Search.failedEdges.add(V5Search.edgeKey(e));
        endUserJump();
        recalculate("your move didn't work");
    }

    /** A point 10 blocks from your eye along {@code yaw}/{@code pitch}, to hold the view on. */
    private static Vec3 lookAlong(LocalPlayer p, float yaw, float pitch) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        return p.getEyePosition().add(-Math.sin(yr) * Math.cos(pr) * 10, -Math.sin(pr) * 10, Math.cos(yr) * Math.cos(pr) * 10);
    }

    /** How far back from the take-off along their line (up to 2.5) there's floor and room to run up. */
    private static double runBack(LocalPlayer p, double tx, double ty, double tz, double ux, double uz) {
        double w = p.getBbWidth() / 2, h = p.getBbHeight(), ok = 0;
        for (double d = 0.25; d <= 2.5 + 1e-6; d += 0.25) {
            double x = tx - ux * d, z = tz - uz * d;
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(x - w, ty + 0.01, z - w, x + w, ty + h, z + w);
            // stop at a wall or where the floor ends
            if (!mc.level.noCollision(p, box) || mc.level.noCollision(p, box.move(0, -0.2, 0))) break;
            ok = d;
        }
        return Math.max(0.5, ok);
    }

    // ── movement helpers ──
    private static void releaseMoveKeys() {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
    }

    /**
     * For the final approach, when the keys toward the goal ({@code gx}, {@code gz} away) don't move you: the key
     * direction (of the eight) that ends nearest the goal after a short move along it and then on toward the goal,
     * tested against the blocks' real shapes. Null if the straight way works or nothing does better. Gets round
     * things like a glass pane's arm at head height.
     */
    private static double[] roundTo(LocalPlayer p, double gx, double gz) {
        double left = Math.hypot(gx, gz);
        if (!p.onGround() || left < 0.3) return null;
        Vec3 goal = p.position().add(gx, 0, gz);
        net.minecraft.world.phys.AABB box = p.getBoundingBox();
        // the straight way moves you: nothing to do
        if (left - flatDist(slideBox(p, box, goal, Double.NaN, 0, 2), goal) > 0.1) return null;
        double yaw = Math.toRadians(p.getYRot());
        double[] best = null;
        double bestLeft = left - 0.15;
        for (int i = 0; i < 8; i++) {
            double a = yaw + i * Math.PI / 4;
            double dx = -Math.sin(a), dz = Math.cos(a);
            net.minecraft.world.phys.AABB side = slideBox(p, box, goal, dx, dz, 4);
            double after = flatDist(slideBox(p, side, goal, Double.NaN, 0, 4), goal);
            if (after < bestLeft) {
                bestLeft = after;
                best = new double[] {dx, dz};
            }
        }
        return best;
    }

    /**
     * Slides the box {@code steps} times 0.15 along ({@code dx}, {@code dz}), or toward {@code goal}
     * when dx is NaN, as far as the blocks allow.
     */
    private static net.minecraft.world.phys.AABB slideBox(LocalPlayer p, net.minecraft.world.phys.AABB box, Vec3 goal, double dx, double dz, int steps) {
        for (int s = 0; s < steps; s++) {
            double mx = dx, mz = dz;
            if (Double.isNaN(dx)) {
                Vec3 c = box.getCenter();
                mx = goal.x - c.x;
                mz = goal.z - c.z;
                double l = Math.hypot(mx, mz);
                if (l < 0.05) break;
                mx /= l;
                mz /= l;
            }
            Vec3 moved = net.minecraft.world.entity.Entity.collideBoundingBox(p, new Vec3(mx * 0.15, 0, mz * 0.15), box, mc.level, List.of());
            box = box.move(moved);
        }
        return box;
    }

    private static double flatDist(net.minecraft.world.phys.AABB box, Vec3 goal) {
        Vec3 c = box.getCenter();
        return Math.hypot(goal.x - c.x, goal.z - c.z);
    }

    /** Presses the one or two of W/A/S/D that push you closest to ({@code dx}, {@code dz}) without turning the view. */
    private static void pressAlong(LocalPlayer p, double dx, double dz) {
        double yaw = Math.toRadians(p.getYRot());
        double fwd = dx * -Math.sin(yaw) + dz * Math.cos(yaw), right = dx * -Math.cos(yaw) + dz * -Math.sin(yaw);
        double len = Math.hypot(fwd, right);
        if (len < 1e-6) return;
        if (!p.onGround() && !dropping()) { // in the air: only W, if ahead (sideways keys push jumps off course)
            mc.options.keyUp.setDown(fwd > 0.5 * len);
            mc.options.keyDown.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyLeft.setDown(false);
            return;
        }
        mc.options.keyUp.setDown(fwd > 0.38 * len);
        mc.options.keyDown.setDown(fwd < -0.38 * len);
        mc.options.keyRight.setDown(right > 0.38 * len);
        mc.options.keyLeft.setDown(right < -0.38 * len);
    }

    /**
     * Falling onto the path's next block below: 0 if not falling onto it or not near it yet (keep W), 1 if over it
     * now (release W), -1 if past it (tap back).
     */
    private static int fallOnto(LocalPlayer p) {
        // only for falls off an edge, not jumps coming down (a pre-jump released W short of its platform)
        if (p.onGround()) airFromJump = false;
        else if (p.getDeltaMovement().y > 0.2) airFromJump = true;
        if (p.onGround() || airFromJump || userJump != null || path == null || p.getDeltaMovement().y > -0.1) return 0;
        int at = Math.max(0, jumps.lastNearestIndex);
        Vec3 v = p.getDeltaMovement();
        double sp = Math.hypot(v.x, v.z);
        for (int k = at; k < Math.min(path.size(), at + 5); k++) {
            BlockPos n = path.get(k);
            if (standTop(n) > p.getY() - 0.4) continue;
            double dx = n.getX() + 0.5 - p.getX(), dz = n.getZ() + 0.5 - p.getZ(), d = Math.hypot(dx, dz);
            if (d > 0.6 + sp * 3) return 0; // still a way off: keep going
            // the path carries on past it in the direction you're going, on its level: don't brake (it
            // braked at every step of a snow slope)
            if (sp > 0.05 && k + 1 < path.size()) {
                BlockPos m = path.get(Math.min(path.size() - 1, k + 2));
                double ox = m.getX() - n.getX(), oz = m.getZ() - n.getZ(), ol = Math.hypot(ox, oz);
                if (ol > 0.5 && (ox * v.x + oz * v.z) / (ol * sp) > 0.8 && Math.abs(standTop(m) - standTop(n)) <= 0.6) return 0;
            }
            if (sp > 0.05 && (dx * v.x + dz * v.z) < 0 && d > 0.35) return -1; // past it
            return 1;
        }
        return 0;
    }

    /** In the air from a jump rather than from a fall off an edge. */
    private static boolean airFromJump;

    /** Falling down a drop that's being taken carefully (its own keys handle the sideways drift). */
    private static boolean dropping() {
        return dropTarget != null;
    }

    /**
     * The next path node below a drop (more than 2 down, within 1.6 across) that has to be landed on
     * exactly, or null. It's crept up to crouched, stepped off a tap at a time and fallen straight down with
     * the keys released ({@link #beginMovement}); pressing on while falling down a narrow shaft caught a
     * ladder on the way. Kept while you're in the air. Drops that land on the path when walked or slid off
     * are just walked off (see {@link #plainDrop}).
     */
    private static BlockPos dropAhead(LocalPlayer p) {
        if (path == null || path.isEmpty()) return dropTarget = null;
        if (!p.onGround()) return dropTarget; // falling (or already off the edge): unchanged
        dropTarget = null;
        int at = nodeAt(p);
        if (at < 0) return null;
        boolean sawDrop = false;
        for (int k = at + 1; k < Math.min(path.size(), at + 3); k++) {
            BlockPos n = path.get(k);
            double down = p.getY() - standTop(n);
            if (down < -0.6) break; // a step up comes first
            // only the first node that far down is the drop; the next node at the bottom isn't another one
            if (down > 2 && Math.hypot(n.getX() + 0.5 - p.getX(), n.getZ() + 0.5 - p.getZ()) >= 1.6) break;
            if (down > 2 && Math.hypot(n.getX() + 0.5 - p.getX(), n.getZ() + 0.5 - p.getZ()) < 1.6) {
                sawDrop = true;
                // once a drop is found walkable, keep it that way until it's taken (re-checked
                // while turning, it could flip to creeping)
                if (n.equals(dropWalked)) {
                    // slid off but stopped short of the edge, still on the ground: walk off from here instead
                    Vec3 v = p.getDeltaMovement();
                    if (dropWalkedCoast && Math.hypot(v.x, v.z) < 0.02) {
                        dropWalked = null;
                        dropNoCoast = n;
                        say("drop to {}: the slide off stopped short of the edge — walked off instead", n.toShortString());
                    } else {
                        dropCoast = dropWalkedCoast;
                        break;
                    }
                }
                if (!n.equals(dropCareful) && plainDrop(p, at, k)) {
                    dropWalked = n;
                    dropWalkedCoast = dropCoast;
                    break;
                }
                dropTarget = n;
                break;
            }
        }
        if (!sawDrop) dropWalked = null;
        if (dropTarget == null) {
            dropCareful = null;
            dropCreepStill = 0;
            dropTapped = false;
            dropLastDist = Double.MAX_VALUE;
            dropTapFrom = null;
            dropTapTicks = 0;
        }
        return dropTarget;
    }

    /**
     * Pre-jumps a step up ahead (a block or more, after level ground) that you're running at, instead of walking up to
     * it and jumping from underneath. Each tick the jump is simulated from your current speed (jump strength,
     * sprint-jump push, W toward the step, air drag, the blocks) and taken on the first tick it clears the step with
     * 0.2 to spare, hitting nothing side-on, and would still clear it from half a block further back. Never from
     * further than {@link #runJumpFor}. While a pre-jump is coming the step isn't turned to ({@link #stepUpAhead}); a
     * step that can't be cleared from a run is walked up to and jumped at as before.
     */
    private static boolean preJump(LocalPlayer p) {
        preJumpable = null;
        if (path == null || path.isEmpty() || p.isInWater() || p.isInLava() || p.onClimbable() || p.isShiftKeyDown()) return false;
        int at = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) { // the node you're on: at your height, nearest horizontally
            BlockPos n = path.get(i);
            if (Math.abs(standTop(n) - p.getY()) > 0.6) continue;
            double d = Math.hypot(n.getX() + 0.5 - p.getX(), n.getZ() + 0.5 - p.getZ());
            if (d < best) {
                best = d;
                at = i;
            }
        }
        if (at < 0 || best > 1.5) return false;
        int step = -1;
        for (int k = at + 1; k < Math.min(path.size(), at + 16); k++) {
            BlockPos n = path.get(k);
            if (Math.hypot(n.getX() + 0.5 - p.getX(), n.getZ() + 0.5 - p.getZ()) > MAX_RUN_JUMP + 1) break;
            // level ground at your height up to it, then a rise of a block or more between two nodes (a
            // gradual snow slope is walked up, not jumped)
            double before = standTop(path.get(k - 1));
            if (before - p.getY() < -0.6 || before - p.getY() > 1.1) break; // level, or a gentle rise (snow)
            if (standTop(n) - before <= STEP_LOOK_FROM) continue;
            BlockPos prev = path.get(k - 1);
            Vec3 fromPrev = k == at + 1 ? p.position() : new Vec3(prev.getX() + 0.5, before, prev.getZ() + 0.5);
            if (entryRise(fromPrev, n) <= Jumps.STEP_HEIGHT) break; // a stair entered from the front: walked up
            step = k;
            break;
        }
        if (step < 0) return false;
        BlockPos s0 = path.get(step);
        double peak = jumpPeak(p);
        List<Integer> targets = jumpTargets(path, step, p.getY(), peak);
        if (targets.isEmpty() || !roomToJump(p)) return false;
        Vec3 v = p.getDeltaMovement();
        double sp = Math.hypot(v.x, v.z);
        double ux = s0.getX() + 0.5 - p.getX(), uz = s0.getZ() + 0.5 - p.getZ(), ul = Math.hypot(ux, uz);
        if (ul < 1e-3) return false;
        // only when running at it; from a standstill or moving across it, walk up to it instead
        if (sp < 0.08 || (v.x * ux + v.z * uz) / (sp * ul) < 0.75) return false;
        // the jump starts one tick's movement on, since the jump key is read next tick
        Vec3 from = p.position().add(v.x, 0, v.z);
        if (!mc.level.noCollision(p, p.getBoundingBox().move(v.x, 0, v.z))) from = p.position();
        int choice = jumpChoice(from, v.x, v.z, p.isSprinting(), p.getYRot(), path, targets, p.getY());
        if (choice == -2) return false;
        preJumpable = s0; // a jump is coming: don't turn to the step meanwhile
        if (choice < 0) return false;
        BlockPos s = path.get(choice);
        say("pre-jump onto {} ({} up, {} off, at {} a tick{})", s.toShortString(),
            String.format(java.util.Locale.US, "%.1f", standTop(s) - p.getY()),
            String.format(java.util.Locale.US, "%.1f", Math.hypot(s.getX() + 0.5 - p.getX(), s.getZ() + 0.5 - p.getZ())),
            String.format(java.util.Locale.US, "%.2f", sp), choice != step ? ", over the steps before it" : "");
        return true;
    }

    /**
     * The steps a jump from before path node {@code step} could land on: that one and any higher ones just after it
     * (rising, no higher than the jump reaches), highest first, so one jump can go straight to the top of a run of
     * steps instead of hopping step by step and bumping its head.
     */
    private static List<Integer> jumpTargets(List<BlockPos> path, int step, double floorY, double peak) {
        List<Integer> out = new ArrayList<>();
        double last = standTop(path.get(step));
        if (last - floorY > peak - 0.05) return out;
        out.add(step);
        for (int k = step + 1; k < Math.min(path.size(), step + 6); k++) {
            double t = standTop(path.get(k));
            if (t < last - 0.3) break; // goes down again
            if (t - floorY > peak - 0.05) break; // higher than the jump reaches
            if (t > last + 0.3) out.add(k);
            last = Math.max(last, t);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * Which of {@code targets} (path indexes, highest first) a jump from {@code from} right now lands on: the highest
     * it clears with 0.2 to spare, hitting nothing side-on first, that it also clears from half a block further back,
     * and that's within {@link #runJumpFor}. Returns -1 for "not yet" (the best target is reachable from closer, or
     * only just reachable from here) and -2 if no target can be reached from here or closer.
     */
    private static int jumpChoice(Vec3 from, double vx, double vz, boolean sprint, float yaw, List<BlockPos> path, List<Integer> targets, double floorY) {
        for (int t : targets) {
            BlockPos s = path.get(t);
            double top = standTop(s);
            Vec3 toward = new Vec3(s.getX() + 0.5, top, s.getZ() + 0.5);
            double ux = toward.x - from.x, uz = toward.z - from.z, ul = Math.hypot(ux, uz);
            if (ul < 0.3) continue;
            if (ul - 0.5 > runJumpFor(top - floorY)) return -1; // still too far away for that one
            double[] c = crossing(from, vx, vz, sprint, yaw, toward, top, null);
            if (c != null && c[0] >= 0.2) {
                double[] back = crossing(from.add(-ux / ul * 0.5, 0, -uz / ul * 0.5), vx, vz, sprint, yaw, toward, top, null);
                return back != null && back[0] >= 0.05 ? t : -1;
            }
            for (double d = 0.5; d <= 3.0; d += 0.5) {
                double[] n = crossing(from.add(ux / ul * d, 0, uz / ul * d), vx, vz, sprint, yaw, toward, top, null);
                if (n != null && n[0] >= 0.2) return -1;
            }
        }
        return -2;
    }

    /** How far back (horizontally) a run-jump onto a step this high may start: 2 × (rise + 1), capped. */
    private static double runJumpFor(double rise) {
        return Math.min(MAX_RUN_JUMP, 2 * (rise + 1));
    }

    private static final double MAX_RUN_JUMP = 11;

    /** The step a pre-jump is coming for (not turned to meanwhile). */
    private static BlockPos preJumpable;

    /** How high a jump lifts your feet, including jump boost. */
    private static double jumpPeak(LocalPlayer p) {
        double vy = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH) + p.getJumpBoostPower(), y = 0;
        for (int t = 0; t < 60 && vy > 0; t++) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
        }
        return y;
    }

    /**
     * Simulates a jump from {@code from} moving {@code vx, vz} per tick (sprinting or not, facing {@code yaw}) with
     * W held toward {@code toward}. Returns {clearance, vy}: how far your feet are above {@code top} on the first
     * tick you're over ground of that height (the step's block or one beside it), and your vertical speed then. Null
     * if it hits something side-on below that height, lands first, or takes over 2 s. With {@code trace}, every
     * tick's feet position is added until landing.
     */
    static double[] crossing(Vec3 from, double vx, double vz, boolean sprint, float yaw, Vec3 toward, double top, List<Vec3> trace) {
        LocalPlayer p = mc.player;
        double vy = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH) + p.getJumpBoostPower();
        if (sprint) { // a sprint jump's push, the way you face
            double yr = Math.toRadians(yaw);
            vx += -Math.sin(yr) * 0.2;
            vz += Math.cos(yr) * 0.2;
        }
        BlockPos under = BlockPos.containing(from.x, from.y - 0.5, from.z);
        float friction = mc.level.getBlockState(under).getBlock().getFriction();
        double speed = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) * (sprint && !p.isSprinting() ? 1.3 : 1);
        double ground = speed * (0.21600002 / (friction * friction * friction)), air = sprint ? 0.025999999 : 0.02;
        Vec3 pos = from;
        double w = p.getBbWidth() / 2, h = p.getBbHeight();
        double[] over = null;
        if (trace != null) trace.add(pos);
        for (int t = 0; t < 40; t++) {
            double dx = toward.x - pos.x, dz = toward.z - pos.z, len = Math.hypot(dx, dz);
            if (len > 0.25) {
                double a = t == 0 ? ground : air;
                vx += a * dx / len;
                vz += a * dz / len;
            }
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos.x - w, pos.y, pos.z - w, pos.x + w, pos.y + h, pos.z + w);
            Vec3 moved = net.minecraft.world.entity.Entity.collideBoundingBox(p, new Vec3(vx, vy, vz), box, mc.level, List.of());
            pos = pos.add(moved);
            if (trace != null) trace.add(pos);
            if (over == null && overGround(pos, w, top)) {
                over = new double[] {pos.y - top, vy};
                if (trace == null) return over;
            }
            boolean side = Math.abs(moved.x - vx) > 1e-7 || Math.abs(moved.z - vz) > 1e-7;
            if (side && over == null && pos.y < top - 0.01) return null; // into its side, or a wall below it
            if (Math.abs(moved.x - vx) > 1e-7) vx = 0;
            if (Math.abs(moved.z - vz) > 1e-7) vz = 0;
            if (Math.abs(moved.y - vy) > 1e-7) {
                if (vy < 0) return over; // landed: on it, or short of it
                vy = 0; // hit a roof: carry on from there
            }
            vy = (vy - 0.08) * 0.98;
            double d = t == 0 ? friction * 0.91 : 0.91;
            vx *= d;
            vz *= d;
        }
        return over;
    }

    /** True if your box (feet at {@code feet}) is over ground whose top is at about {@code top}. */
    private static boolean overGround(Vec3 feet, double w, double top) {
        // feet below the top aren't over it, and neither is a box only touching its side (pressed against the wall
        // after a bump), or it jumped at the wall instead of from a distance
        if (feet.y < top - 0.05) return false;
        int y = (int) Math.floor(top - 0.001);
        double e = 1e-3;
        for (int x = (int) Math.floor(feet.x - w + e); x <= (int) Math.floor(feet.x + w - e); x++)
            for (int z = (int) Math.floor(feet.z - w + e); z <= (int) Math.floor(feet.z + w - e); z++) {
                BlockPos b = new BlockPos(x, y, z);
                var shape = mc.level.getBlockState(b).getCollisionShape(mc.level, b);
                if (!shape.isEmpty() && Math.abs(y + shape.max(Direction.Axis.Y) - top) < 0.3) return true;
            }
        return false;
    }

    /** True if node {@code k} of {@code path} needs a jump: a block or more above the previous node, not a stair. */
    public static boolean jumpStep(List<BlockPos> path, int k) {
        if (k < 1 || k >= path.size()) return false;
        BlockPos a = path.get(k - 1), b = path.get(k);
        double before = standTop(a);
        if (standTop(b) - before <= STEP_LOOK_FROM) return false;
        return entryRise(new Vec3(a.getX() + 0.5, before, a.getZ() + 0.5), b) > Jumps.STEP_HEIGHT;
    }

    /**
     * The pre-jump {@link #preJump} would make onto step {@code k} when running at {@code speed} along the
     * path, as feet positions tick by tick until landing (for drawing the route), or null if there's none.
     * Not called in this mod; kept from the Java mod.
     */
    public static List<Vec3> preJumpArc(List<BlockPos> path, int k, double speed) {
        if (!jumpStep(path, k) || mc.player == null) return null;
        BlockPos s0 = path.get(k);
        double floor = standTop(path.get(k - 1));
        double peak = jumpPeak(mc.player);
        List<Integer> targets = jumpTargets(path, k, floor, peak);
        if (targets.isEmpty()) return null;
        double reachBack = runJumpFor(standTop(path.get(targets.get(0))) - floor);
        Vec3 first = new Vec3(s0.getX() + 0.5, standTop(s0), s0.getZ() + 0.5);
        // step back along the incoming path a tenth of a block at a time, and use the furthest spot it would jump from
        List<Vec3> back = new ArrayList<>();
        Vec3 at = new Vec3(path.get(k - 1).getX() + 0.5, floor, path.get(k - 1).getZ() + 0.5);
        back.add(at);
        double run = 0;
        for (int i = k - 2; i >= 0 && run < reachBack + 1; i--) {
            BlockPos n = path.get(i);
            double nt = standTop(n);
            if (nt - floor > 0.6 || floor - nt > 1.1) break;
            Vec3 q = new Vec3(n.getX() + 0.5, nt, n.getZ() + 0.5);
            double seg = q.distanceTo(at);
            for (double d = 0.1; d <= seg; d += 0.1) { // standing on the nearer node's ground (a snow slope's steps)
                Vec3 m = at.add(q.subtract(at).scale(d / seg));
                back.add(new Vec3(m.x, d / seg < 0.5 ? at.y : q.y, m.z));
            }
            run += seg;
            at = q;
        }
        for (int i = back.size() - 1; i >= 0; i--) {
            Vec3 from = back.get(i);
            double dx = first.x - from.x, dz = first.z - from.z, len = Math.hypot(dx, dz);
            if (len < 0.3) break;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            double vx = dx / len * speed, vz = dz / len * speed;
            int choice = jumpChoice(from, vx, vz, true, yaw, path, targets, from.y);
            if (choice < 0) continue;
            BlockPos s = path.get(choice);
            List<Vec3> arc = new ArrayList<>();
            crossing(from, vx, vz, true, yaw, new Vec3(s.getX() + 0.5, standTop(s), s.getZ() + 0.5), standTop(s), arc);
            return arc;
        }
        return null;
    }

    /** The node the step is climbed from; high steps are jumped from its middle, where the search checked headroom. */
    private static BlockPos stepFrom;

    /** The step up being taken: set on the ground, kept through the jump onto it. */
    private static BlockPos stepTarget;
    /** A rise of at least this (about a block) is a step to turn toward; anything lower is jumped on the way. */
    private static final double STEP_LOOK_FROM = 0.95;

    /**
     * The next path node a block or more up from where you stand, within 2 across, or null. It's looked at and walked
     * straight at until you're up on it; with the normal look point already round the next corner, a spiral of steps
     * had it pushing into the corner below each step.
     */
    private static BlockPos stepUpAhead(LocalPlayer p) {
        if (path == null || path.isEmpty()) return stepTarget = null;
        if (!p.onGround()) {
            if (p.getDeltaMovement().y < -0.5) stepTarget = null; // falling, not a jump up a step
            return stepTarget;
        }
        stepTarget = null;
        int at = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) { // the node you're on: at your height, nearest horizontally
            BlockPos n = path.get(i);
            if (Math.abs(standTop(n) - p.getY()) > 0.6) continue;
            double d = Math.hypot(n.getX() + 0.5 - p.getX(), n.getZ() + 0.5 - p.getZ());
            if (d < best) {
                best = d;
                at = i;
            }
        }
        if (at < 0 || best > 1.5) return null;
        for (int k = at + 1; k < Math.min(path.size(), at + 3); k++) {
            BlockPos n = path.get(k);
            double rise = standTop(n) - p.getY();
            // level, a normal step, or up onto deeper snow or a slab: keep looking ahead and jump on contact (turning
            // to the middle of each of those made the view swing all the way up a snow slope)
            if (rise <= STEP_LOOK_FROM) continue;
            // a stair entered from the front is walked up; measure each stair from the one before, or the second
            // stair of a staircase looked far too high
            BlockPos prev = path.get(k - 1);
            Vec3 from = k == at + 1 ? p.position() : new Vec3(prev.getX() + 0.5, standTop(prev), prev.getZ() + 0.5);
            if (entryRise(from, n) <= Jumps.STEP_HEIGHT) continue;
            double across = Math.hypot(n.getX() + 0.5 - p.getX(), n.getZ() + 0.5 - p.getZ());
            // a higher step within jump range is looked at steadily, walked to and jumped
            // (the normal look can't see up onto it)
            if (n.equals(preJumpable)) break; // a pre-jump onto it from a distance is coming: keep looking ahead
            // up to the full height of your jump, since with jump boost the path can include taller climbs
            if (rise <= Math.max(Math.max(1.3, V5Search.maxClimb + 0.8), jumpPeak(p) - 0.02) && across < 2.0 && across > 0.3) {
                stepTarget = n;
                stepFrom = path.get(k - 1);
            }
            break;
        }
        return stepTarget;
    }

    /**
     * How far up you step to get onto {@code node} from where you are: the top of the part of the block under it
     * nearest you (0.4 deep). A stair's low half is only half a block, so it's walked up rather than jumped.
     */
    static double entryRise(LocalPlayer p, BlockPos node) {
        return entryRise(p.position(), node);
    }

    /** As above, from feet at {@code feet}. */
    public static double entryRise(Vec3 feet, BlockPos node) {
        Level level = mc.level;
        BlockPos b = node.below();
        var shape = level.getBlockState(b).getCollisionShape(level, b);
        if (shape.isEmpty()) {
            b = node;
            shape = level.getBlockState(b).getCollisionShape(level, b);
        }
        if (shape.isEmpty()) return node.getY() - feet.y;
        double dx = b.getX() + 0.5 - feet.x, dz = b.getZ() + 0.5 - feet.z;
        net.minecraft.world.phys.AABB strip = Math.abs(dx) > Math.abs(dz)
            ? (dx > 0 ? new net.minecraft.world.phys.AABB(0, 0, 0, 0.4, 1, 1) : new net.minecraft.world.phys.AABB(0.6, 0, 0, 1, 1, 1))
            : (dz > 0 ? new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 0.4) : new net.minecraft.world.phys.AABB(0, 0, 0.6, 1, 1, 1));
        double top = -1;
        for (net.minecraft.world.phys.AABB a : shape.toAabbs()) if (a.intersects(strip)) top = Math.max(top, a.maxY);
        if (top < 0) top = shape.max(net.minecraft.core.Direction.Axis.Y);
        return b.getY() + top - feet.y;
    }

    /** Feet height at path node {@code g}: the top of what's in it (snow layer, slab) or of the block below. */
    public static double groundTop(BlockPos g) {
        return standTop(g);
    }

    private static double standTop(BlockPos g) {
        Level level = mc.level;
        var in = level.getBlockState(g).getCollisionShape(level, g);
        if (!in.isEmpty()) return g.getY() + in.max(net.minecraft.core.Direction.Axis.Y);
        BlockPos b = g.below();
        var under = level.getBlockState(b).getCollisionShape(level, b);
        return under.isEmpty() ? g.getY() : b.getY() + under.max(net.minecraft.core.Direction.Axis.Y);
    }

    /** Within this distance of the goal: walk, don't sprint, and turn on the spot if it's well to the side. */
    private static final double FINAL_APPROACH = 3.0;
    /** When the look direction is at least this far from where you face, turn before walking on. */
    private static final float TURN_FIRST_DEG = 45f;
    private static long turnFirstSince;
    private static int turnFirstTicks;

    /**
     * Finds where the look line turns 40° or more within your next 8 ticks of running (at least 2 blocks):
     * returns {distance to it, angle}, or null.
     */
    private static double[] cornerAhead(LocalPlayer p, double sp) {
        if (rot.pts == null || rot.pts.size() < 3) return null;
        Vec3 a = rot.at(rot.pos), b = rot.at(rot.pos + 1.0);
        double ax = b.x - a.x, az = b.z - a.z, la = Math.hypot(ax, az);
        if (la < 0.3) return null;
        double reach = Math.max(2.0, sp * 8);
        for (double t = rot.pos + 1.0; t <= rot.lastIndex() - 0.5; t += 0.5) {
            Vec3 c = rot.at(t), d = rot.at(Math.min(rot.lastIndex(), t + 1.0));
            double dist = Math.hypot(c.x - p.getX(), c.z - p.getZ());
            if (dist > reach) break;
            double bx = d.x - c.x, bz = d.z - c.z, lb = Math.hypot(bx, bz);
            if (lb < 0.3) continue;
            double cos = (ax * bx + az * bz) / (la * lb);
            if (cos < 0.766) return new double[] {dist, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))))};
        }
        return null;
    }
    /**
     * Between STRAFE_INTO_TURN_DEG and {@link #TURN_FIRST_DEG} off, it walks on while strafing
     * toward the look direction, stopping under STRAFE_LET_GO_DEG. Forward plus strafe moves 45° off
     * your facing, so starting at 15° over-turned.
     */
    private static final float STRAFE_INTO_TURN_DEG = 30f, STRAFE_LET_GO_DEG = 18f;
    /** A strafe isn't switched to the other side within this long of starting. */
    private static final long STRAFE_SWITCH_MS = 300;
    private static int strafeSide = 0;
    private static long strafeSince = 0;

    /** Within this distance of the goal, the movement keys take over and the view is held (see beginMovement). */
    private static final double FINAL_KEYS = 2.0;
    private static boolean finalKeys;
    private static long onGoalSince;

    private static BlockPos nearestGoal(LocalPlayer p) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos g : goals) {
            double dy = p.getY() - g.getY();
            if (dy < -1.1 || dy > 1.5) continue; // level with you only (not a goal down a drop)
            double d = Math.hypot(p.getX() - (g.getX() + 0.5), p.getZ() - (g.getZ() + 0.5));
            if (d < bestD) {
                bestD = d;
                best = g;
            }
        }
        return best;
    }

    private static void releaseKeys() {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        // a crouched walk ends crouched; whatever comes next decides whether to stand up
        if (!shiftWalk) mc.options.keyShift.setDown(false);
    }

    // ═══════════════════════════════════ spline (PathSpline) ═══════════════════════════════════

    /**
     * The path as points every 0.4 blocks (block centres, at the floor block's height), skipping nodes within
     * {@code tolerance} of the last kept one where the body clears the cut. V5's PathSpline; not called at the moment,
     * the look line comes from {@link #lineLookPoints}.
     */
    static List<Vec3> generateSpline(List<BlockPos> nodes, double tolerance) {
        List<Vec3> raw = new ArrayList<>(nodes.size());
        // use the floor you'd actually stand on: V5 treats snow of 2+ layers as a full block, so the line dropped on
        // level ground and the walk took it for a ledge
        boolean real = mc != null && mc.level != null;
        for (BlockPos n : nodes) raw.add(new Vec3(n.getX() + 0.5, real ? standTop(n) - 1 : n.getY() - 1, n.getZ() + 0.5));
        List<Vec3> simplified = new ArrayList<>();
        simplified.add(raw.get(0));
        for (int i = 1; i < raw.size() - 1; i++) {
            Vec3 last = simplified.get(simplified.size() - 1);
            // skip a nearby node for a smoother line, but not at a corner the body can't
            // cut (the line went through the stone)
            if (raw.get(i).distanceTo(last) > tolerance || real && !cutClear(last, raw.get(i + 1))) simplified.add(raw.get(i));
        }
        simplified.add(raw.get(raw.size() - 1));
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < simplified.size() - 1; i++) {
            Vec3 a = simplified.get(i), b = simplified.get(i + 1);
            Vec3 d = b.subtract(a);
            int steps = (int) Math.ceil(d.length() / 0.4);
            for (int s = 0; s < steps; s++) out.add(a.add(d.scale((double) s / steps)));
        }
        out.add(simplified.get(simplified.size() - 1));
        return out;
    }

    /**
     * True if the body clears a straight cut from spline point {@code a} to {@code b} (their floors a block below the
     * feet), checked every quarter block from a step's height up to the head, with a little to spare each side.
     */
    private static boolean cutClear(Vec3 a, Vec3 b) {
        Level level = mc.level;
        if (level == null || mc.player == null) return true;
        double len = Math.hypot(b.x - a.x, b.z - a.z);
        int steps = Math.max(1, (int) Math.ceil(len / 0.25));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            // feet at the higher of the two points
            double x = a.x + (b.x - a.x) * t, z = a.z + (b.z - a.z) * t, feet = Math.max(a.y, b.y) + 1;
            if (!level.noCollision(mc.player, new net.minecraft.world.phys.AABB(x - 0.35, feet + 0.6, z - 0.35, x + 0.35, feet + 1.8, z + 0.35)))
                return false;
        }
        return true;
    }

    private static boolean insideBlock(Vec3 p) {
        Level level = mc.level;
        if (level == null) return false;
        BlockPos b = BlockPos.containing(p);
        try {
            return !level.getBlockState(b).getCollisionShape(level, b).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static Vec3 adjustLookPoint(Vec3 point, Vec3 node) {
        if (!insideBlock(point)) return point;
        Vec3 unoffset = new Vec3(node.x, point.y, node.z);
        if (!insideBlock(unoffset)) return unoffset;
        Vec3 lowered = new Vec3(node.x, point.y - 0.5, node.z);
        return insideBlock(lowered) ? unoffset : lowered;
    }

    private static void appendLookPoint(List<Vec3> points, Vec3 p) {
        if (points.isEmpty()) {
            points.add(p);
            return;
        }
        Vec3 last = points.get(points.size() - 1);
        double dx = p.x - last.x, dz = p.z - last.z;
        if (dx * dx + dz * dz < 0.8 * 0.8) points.set(points.size() - 1, p);
        else points.add(p);
    }

    /**
     * Look points along the spline at eye height: sparse on straights ({@code maxInterval}), dense on bends
     * ({@code minInterval}), pushed out on bends by up to 1.2 blocks, never doubling back. Goes with
     * {@link #generateSpline}; not called at the moment.
     */
    static List<Vec3> createLookPoints(List<Vec3> path, double minInterval, double maxInterval) {
        List<Vec3> points = new ArrayList<>();
        if (path.size() < 2) return points;
        points.add(path.get(0).add(0, 2.62, 0));
        Vec3 lastPlaced = path.get(0);
        double[] lastDir = null;
        for (int i = 1; i < path.size() - 1; i++) {
            Vec3 cur = path.get(i);
            double distance = cur.distanceTo(lastPlaced);
            Vec3 prev = path.get(Math.max(0, i - 4)), next = path.get(Math.min(path.size() - 1, i + 4));
            double bx = cur.x - prev.x, bz = cur.z - prev.z, ax = next.x - cur.x, az = next.z - cur.z;
            double bl = Math.hypot(bx, bz), al = Math.hypot(ax, az);
            double curvature = 0, offX = 0, offZ = 0;
            if (bl > 0.05 && al > 0.05) {
                double dot = (bx * ax + bz * az) / (bl * al);
                curvature = Math.min(Math.acos(Math.max(-1, Math.min(1, dot))) / (Math.PI / 2.5), 1);
                int dir = bx * az - bz * ax > 0 ? 1 : -1;
                double fx = bx / bl + ax / al, fz = bz / bl + az / al, fl = Math.hypot(fx, fz);
                if (fl > 0.01) {
                    offX = -(fz / fl) * dir * curvature * 1.2;
                    offZ = (fx / fl) * dir * curvature * 1.2;
                }
            }
            if (distance < maxInterval - curvature * (maxInterval - minInterval)) continue;
            double fx = cur.x - lastPlaced.x, fz = cur.z - lastPlaced.z, fl = Math.hypot(fx, fz);
            if (lastDir != null && fl > 0.1 && distance < 12) {
                double dot = (fx * lastDir[0] + fz * lastDir[1]) / fl;
                if (dot < 0.4) continue;
            }
            // swing wide on a bend only as far as there's room, or it steers off the path into the wall
            double room = roomOut(cur, offX, offZ);
            appendLookPoint(points, adjustLookPoint(new Vec3(cur.x + offX * room, cur.y + 2.62, cur.z + offZ * room), cur));
            lastPlaced = cur;
            if (fl > 0.1) lastDir = new double[]{fx / fl, fz / fl};
        }
        Vec3 end = path.get(path.size() - 1);
        appendLookPoint(points, end.add(0, 2.62, 0));
        return points;
    }

    /** How much of a bend's push-out from {@code cur} has room (1, 0.5 or 0), using {@link #AROUND_BODY}. */
    private static double roomOut(Vec3 cur, double offX, double offZ) {
        if (Math.abs(offX) < 0.05 && Math.abs(offZ) < 0.05) return 1;
        V5World w = V5World.shared();
        if (w == null) return 1;
        int feetY = (int) Math.round(cur.y + 1);
        for (double f : new double[]{1.0, 0.5}) {
            boolean fits = true;
            for (double t : new double[]{0.5, 1.0}) {
                double x = cur.x + offX * f * t, z = cur.z + offZ * f * t;
                for (double[] o : AROUND_BODY) {
                    if (!V5Search.standable(w, (int) Math.floor(x + o[0]), feetY, (int) Math.floor(z + o[1]))) {
                        fits = false;
                        break;
                    }
                }
                if (!fits) break;
            }
            if (fits) return f;
        }
        return 0;
    }

    /**
     * Offsets around the body with room to spare (0.45 out, corners included) that a cut corner or wide
     * swing must keep clear. With just the body's 0.3 a cut went through a barely-wide-enough doorway,
     * the steering drifted, and it hit the post.
     */
    private static final double[][] AROUND_BODY = {{0, 0}, {0.45, 0}, {-0.45, 0}, {0, 0.45}, {0, -0.45},
            {0.45, 0.45}, {0.45, -0.45}, {-0.45, 0.45}, {-0.45, -0.45}};

    // ═══════════════════════════════════ rotations (PathRotations) ═══════════════════════════════════

    private static final Rot rot = new Rot();

    private static final class Rot {
        // in blocks along the line (look points are a block apart); much shorter and the steering weaves at high speed
        static final double MIN_LOOKAHEAD = 1.8, MAX_LOOKAHEAD = 4.5, RECOVERY_MIN_LOOKAHEAD = 0.1;
        /** On the level with the look point this close, pitch is limited to ±{@link #WALK_PITCH_MAX}. */
        static final double WALK_PITCH_NEAR = 2.5;
        static final float WALK_PITCH_MAX = 30f;
        static final double PROXIMITY_THRESHOLD = 4.0, LOOKAHEAD_STEP = 0.4, RECOVERY_LOOKAHEAD_STEP = 0.15;
        static final double MAX_DIRECTION_DIVERGENCE = 50.0, MAX_UPWARD_PITCH = -45.0;
        /** A bend whose inside is open can be cut across up to this far off the way ahead (see openBend). */
        static final double OPEN_BEND_DIVERGENCE = 100.0;

        /** On the ground, look point on your level, and a straight walk to it over floor with nothing in the way. */
        boolean openBend(Vec3 point) {
            LocalPlayer p = mc.player;
            if (p == null || !p.onGround()) return false;
            double feet = point.y - 1.62;
            if (Math.abs(feet - p.getY()) > 0.3) return false;
            return floorAlong(p.position(), new Vec3(point.x, feet, point.z), p.getY(), 0.4) && bodyClear(point);
        }
        static final int PREDICTION_TICKS = 10;
        static final double PREDICTION_MIN_SPEED_XZ = 0.05, PREDICTION_MAX_ADVANCE_GROUND = 0.9, PREDICTION_MAX_ADVANCE_AIR = 2.4;
        static final double MIN_HORIZONTAL_LOOK = 1.2;
        /** Dropping or falling: look at least this far ahead and at most {@link #FALL_PITCH_MAX} down. */
        static final double FALL_LOOK_AHEAD = 3.0;
        static final float FALL_PITCH_MAX = 35f;

        final V5PathRotation pd = new V5PathRotation();
        List<Vec3> pts;
        double pos;
        boolean began, complete;
        double smoothedLookahead = MAX_LOOKAHEAD;
        Double lookaheadOverride;
        int lookaheadOverrideTicks;
        long unseenSince;
        double unseenStartPos;
        double curvature;
        long lastFrame;
        /** The yaw the walk last turned toward (NaN before its first frame), and the pitch. */
        float lookYaw = Float.NaN, lookPitch = Float.NaN;

        void reset() {
            lookYaw = Float.NaN;
            lookPitch = Float.NaN;
            pts = null;
            pos = 0;
            began = false;
            complete = false;
            smoothedLookahead = MAX_LOOKAHEAD;
            chosenLookahead = Double.NaN;
            lookaheadOverride = null;
            lookaheadOverrideTicks = 0;
            unseenSince = 0;
            unseenStartPos = 0;
            curvature = 0;
            lastFrame = 0;
            resyncTicks = 0;
            pd.reset();
        }

        /** Starts the walk's turning (V5's pathRotations) from wherever you're looking, at rest. */
        void begin() {
            if (began) return;
            began = true;
            pd.reset();
        }

        int resyncTicks;

        void tick() {
            if (lookaheadOverrideTicks > 0 && --lookaheadOverrideTicks <= 0) lookaheadOverride = null;
            if (resyncTicks > 0) resyncTicks--;
        }

        /** After an AOTV cast toward {@code target}, finds you on the path again where you land (unused here). */
        void onTeleport(double target) {
            resyncTicks = 14;
            unseenSince = 0;
            unseenStartPos = pos;
            setTemporaryLookahead(MAX_LOOKAHEAD, 14);
            pos = Math.max(pos, Math.max(0, target - 2.0));
        }

        int lastIndex() {
            return pts == null ? 0 : pts.size() - 1;
        }

        void setTemporaryLookahead(double distance, int ticks) {
            lookaheadOverride = distance;
            lookaheadOverrideTicks = ticks;
            smoothedLookahead = distance;
        }

        boolean recoveryMode() {
            return lookaheadOverride != null && lookaheadOverrideTicks > 0;
        }

        Vec3 at(double t) {
            if (pts == null || pts.isEmpty()) return null;
            double s = Double.isFinite(t) ? Math.max(0, Math.min(t, pts.size() - 1)) : 0;
            int i = (int) Math.floor(s);
            double frac = s - i;
            Vec3 a = pts.get(i), b = pts.get(Math.min(i + 1, pts.size() - 1));
            if (frac <= 0) return a;
            return new Vec3(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac, a.z + (b.z - a.z) * frac);
        }

        void frame(LocalPlayer player) {
            if (!began || complete || pts == null || pts.size() < 2) return;
            long now = System.currentTimeMillis();
            double ts = lastFrame == 0 ? 1 : Math.min(2, (now - lastFrame) / (1000 / 120.0));
            lastFrame = now;
            float[] target = updateLookPoint(player, ts);
            if (target == null) return;
            pd.setPathShape(curvature, lastIndex() - pos);
            pd.setUrgency(wallAhead(player, target[0]));
            float pitch = overridePitch != null ? overridePitch : target[1];
            noteSway(target[0]);
            lookYaw = target[0];
            lookPitch = pitch;
            float[] a = pd.step(player.getYRot(), player.getXRot(), target[0], pitch, player.onGround(), target[2] != 0, ts);
            player.setYRot(a[0]);
            player.setXRot(a[1]);
        }

        // ── sway detection: logs when the look target's yaw keeps flipping back and forth ──
        private float swayLast = Float.NaN, swayLastTurn;
        private final java.util.ArrayDeque<Long> swayFlips = new java.util.ArrayDeque<>();
        private long swaySaidAt;
        int bodyFails, unseenFails, bendFails; // since the last log line: why the look point was pulled in

        private void noteSway(float yawNow) {
            long now = System.currentTimeMillis();
            if (!Float.isNaN(swayLast)) {
                float turn = net.minecraft.util.Mth.wrapDegrees(yawNow - swayLast);
                if (Math.abs(turn) > 10) {
                    if (swayLastTurn != 0 && Math.signum(turn) != Math.signum(swayLastTurn)) swayFlips.add(now);
                    swayLastTurn = turn;
                    swayLast = yawNow;
                }
            } else swayLast = yawNow;
            while (!swayFlips.isEmpty() && now - swayFlips.peekFirst() > 1500) swayFlips.pollFirst();
            if (swayFlips.size() >= 3 && now - swaySaidAt > 3000 && mc.player != null) {
                swaySaidAt = now;
                say("swaying at {} (look target turned back and forth {}x in 1.5 s): lookahead {} of {}, pulled in by body {} / unseen {} / bend {}, step {}, recovery {}",
                    mc.player.blockPosition().toShortString(), swayFlips.size(), String.format(java.util.Locale.US, "%.1f", lastLookahead),
                    String.format(java.util.Locale.US, "%.1f", smoothedLookahead), bodyFails, unseenFails, bendFails, stepTarget, recoveryLook != null);
                bodyFails = unseenFails = bendFails = 0;
            }
        }

        double lastLookahead;
        /** Look-ahead in use: drops at once, grows by at most {@link #LOOKAHEAD_GROW} per frame (at 120 fps). */
        double chosenLookahead = Double.NaN;
        static final double LOOKAHEAD_GROW = 0.04;

        /**
         * How urgently to turn: on the ground with the way more than 15° to the side, 1 if the body
         * would hit something within 0.6 ahead, 0.5 within 1.2, else 0. Only counts things above a
         * block's height, so a step up isn't in the way.
         */
        double wallAhead(LocalPlayer player, float wantYaw) {
            if (!player.onGround() || mc.level == null) return 0;
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(wantYaw - player.getYRot())) < 15) return 0;
            double yaw = Math.toRadians(player.getYRot());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            net.minecraft.world.phys.AABB box = player.getBoundingBox();
            // above a block's height only: steps are jumped, not turned away from (from 0.6 up,
            // a snow slope was a wall the whole way)
            box = new net.minecraft.world.phys.AABB(box.minX, box.minY + 1.01, box.minZ, box.maxX, box.maxY, box.maxZ);
            if (!mc.level.noCollision(player, box.move(fx * 0.6, 0, fz * 0.6))) return 1;
            if (!mc.level.noCollision(player, box.move(fx * 1.2, 0, fz * 1.2))) return 0.5;
            return 0;
        }

        /** One frame's turn toward {@code point} (at the end of a walk: whatever comes next). */
        void lookAt(LocalPlayer player, Vec3 point) {
            long now = System.currentTimeMillis();
            double ts = lastFrame == 0 ? 1 : Math.min(2, (now - lastFrame) / (1000 / 120.0));
            lastFrame = now;
            // this frame's (interpolated) eye position
            Vec3 eye = player.getEyePosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
            double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.max(-90, Math.min(90, -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))));
            pd.setPathShape(curvature, 0.3); // at the end: follow it closely
            float[] a = pd.step(player.getYRot(), player.getXRot(), yaw, pitch, player.onGround(), false, ts);
            player.setYRot(a[0]);
            player.setXRot(a[1]);
        }

        /** The {yaw, pitch, holdYaw} to turn toward this frame, or null. */
        float[] updateLookPoint(LocalPlayer player, double ts) {
            Vec3 eyes = player.getEyePosition();
            double motionY = player.getDeltaMovement().y;
            boolean falling = motionY < -0.4;
            boolean dropping = pathDropping();
            Vec3 anchor = at(pos);
            if (anchor == null) return null;
            boolean jumpingHigh = motionY > 0.1 || player.getY() - anchor.y > 2.0;

            double bestT = pos, minDistSq = Double.MAX_VALUE;
            boolean resync = resyncTicks > 0;
            int window = falling ? 4 : jumpingHigh ? 12 : 8;
            int startIdx = falling ? (int) Math.floor(pos) : Math.max(0, (int) Math.floor(pos) - 2);
            if (resync) {
                window = Math.max(window, 72);
                startIdx = Math.max(0, (int) Math.floor(pos) - 24);
            }
            int endIdx = Math.min(pts.size() - 2, startIdx + window);
            for (int i = startIdx; i <= endIdx; i++) {
                Vec3 p1 = pts.get(i), p2 = pts.get(i + 1);
                double prog = falling || jumpingHigh ? closestHorizontal(eyes, p1, p2) : closest3d(eyes, p1, p2);
                double cand = i + prog;
                if (falling && cand < pos) continue;
                Vec3 proj = at(cand);
                double d = falling ? sq(eyes.x - proj.x) + sq(eyes.z - proj.z) : eyes.distanceToSqr(proj);
                if (d < minDistSq) {
                    minDistSq = d;
                    bestT = cand;
                }
            }
            double threshold = (falling ? 5.0 : jumpingHigh ? PROXIMITY_THRESHOLD * 2 : PROXIMITY_THRESHOLD) * (resync ? 1.8 : 1);
            if (minDistSq < threshold * threshold) {
                double maxJump = resync ? 14.0 : falling ? 0.5 : 2.0;
                // Don't move back for a small difference (wide round a bend or up a step the nearest
                // point can be a block behind, and the view swung back); only when you're well behind it,
                // e.g. after a jump that came down short.
                double next = Math.min(pos + maxJump, bestT);
                if (next >= pos || resync || pos - next > 1.5) pos = next;
            }
            if (!resync && !dropping) applyPredictedProgress(player);

            double adaptive = adaptiveLookahead(eyes, ts);
            Object[] result = findVisibleLookahead(eyes, adaptive);
            Vec3 point = (Vec3) result[0];
            double lookahead = (Double) result[1];
            // pull the look-ahead in immediately but let it out slowly, so a far point that flickers in and
            // out of view doesn't make the view sway
            if (Double.isNaN(chosenLookahead) || lookahead < chosenLookahead) chosenLookahead = lookahead;
            else chosenLookahead = Math.min(lookahead, chosenLookahead + LOOKAHEAD_GROW * ts);
            if (chosenLookahead < lookahead - 1e-6) {
                point = at(Math.min(pts.size() - 1, pos + chosenLookahead));
                lookahead = chosenLookahead;
            }
            double effectiveMin = recoveryMode() ? RECOVERY_MIN_LOOKAHEAD : MIN_LOOKAHEAD;
            boolean visible = visible(eyes, point, true);
            long now = System.currentTimeMillis();
            if (lookahead <= effectiveMin + 0.001 && !visible) {
                if (unseenSince == 0) {
                    unseenSince = now;
                    unseenStartPos = pos;
                }
                // rolling back mid-air puts the look point behind you and turns the camera round
                if (now - unseenSince >= 600 && player.onGround()) {
                    int attempts = 0;
                    // never roll back behind where you are on the path, or it walks backwards
                    double minRollback = Math.max(0, Math.max(unseenStartPos - 8, projectHorizontal(player.getX(), player.getZ(), pos) - 0.3));
                    while (pos > minRollback && attempts < 8) {
                        pos = Math.max(minRollback, pos - 1);
                        double t = Math.min(pts.size() - 1, pos + effectiveMin);
                        if (visible(eyes, at(t), true)) {
                            unseenSince = 0;
                            unseenStartPos = pos;
                            result = findVisibleLookahead(eyes, adaptive);
                            point = (Vec3) result[0];
                            lookahead = (Double) result[1];
                            break;
                        }
                        attempts++;
                    }
                }
            } else {
                unseenSince = 0;
                unseenStartPos = pos;
            }
            if (lookahead < smoothedLookahead) smoothedLookahead = smoothedLookahead * 0.9 + lookahead * 0.1;
            // a step up just ahead: look at its middle until you're up on it (see stepUpAhead)
            BlockPos step = stepUpAhead(player);
            if (userJumpLook != null) point = userJumpLook; // replaying a recorded jump: look where it goes
            else if (step != null) {
                point = new Vec3(step.getX() + 0.5, standTop(step) + 1.0, step.getZ() + 0.5);
                // look toward it, at most 30° up, rather than up at the roof above a tall step
                double h = Math.max(0.5, Math.hypot(point.x - eyes.x, point.z - eyes.z));
                point = new Vec3(point.x, Math.min(point.y, eyes.y + h * Math.tan(Math.toRadians(30))), point.z);
            }
            else if (recoveryLook != null) point = recoveryLook; // stuck: back onto the path, see handleRecovery

            double rdx = point.x - eyes.x, rdy = point.y - eyes.y, rdz = point.z - eyes.z;
            double rawHorz = Math.hypot(rdx, rdz);
            double rawPitch = -Math.toDegrees(Math.atan2(rdy, rawHorz));
            if (rawPitch < -50 && rawHorz < 1.0) {
                point = new Vec3(point.x, eyes.y + rawHorz * Math.tan(Math.toRadians(30)), point.z);
            }
            // A look point almost straight below gives a yaw that spins the camera while falling, and so does one
            // you're passing over or under (a step's middle while jumping onto it). Hold the yaw then.
            boolean holdYaw = rawHorz < (step != null ? 0.7 : 0.3);
            // off a ledge or falling: look along the path (at least 3 blocks out), not down at the landing spot
            boolean airborne = !player.onGround() || falling || dropping;
            Vec3 land = player.onGround() && !falling && dropping && step == null && recoveryLook == null && userJumpLook == null
                    ? dropLanding() : null;
            if (land != null) {
                // still on the ledge: look out over the edge toward the landing, not along a
                // path that may double back underneath
                double hx = land.x - eyes.x, hz = land.z - eyes.z, hl = Math.hypot(hx, hz);
                if (hl > 0.2) {
                    double out = Math.max(hl, FALL_LOOK_AHEAD);
                    point = new Vec3(eyes.x + hx / hl * out, land.y + 1.0, eyes.z + hz / hl * out);
                    holdYaw = false;
                }
            } else if (airborne && step == null && recoveryLook == null && rawHorz < FALL_LOOK_AHEAD) {
                // airborne and not jumping up a step (that's looked at directly): look on along the path
                Vec3 far = horizontallyDistantPoint(eyes, FALL_LOOK_AHEAD);
                if (far == null) far = horizontallyDistantPoint(eyes, MIN_HORIZONTAL_LOOK + 0.3);
                if (far != null) point = far;
                else if (rawHorz < MIN_HORIZONTAL_LOOK) holdYaw = true;
            }
            // angle from this frame's interpolated eye position, not the last tick's; with a close look point the
            // per-tick position made the aim jump and the view jerk up and down
            Vec3 eyesNow = player.getEyePosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
            double dx = point.x - eyesNow.x, dy = point.y - eyesNow.y, dz = point.z - eyesNow.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.max(-90, Math.min(90, -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))));
            // on the ground and level: keep the pitch gentle so a nearby look point doesn't swing the view up and down
            if (player.onGround() && !falling && !dropping && !jumpingHigh && Math.hypot(dx, dz) < WALK_PITCH_NEAR) {
                pitch = Math.max(-WALK_PITCH_MAX, Math.min(WALK_PITCH_MAX, pitch));
            }
            // while airborne: at most 20° up and FALL_PITCH_MAX down
            if (airborne) pitch = Math.max(-20f, Math.min(FALL_PITCH_MAX, pitch));

            // done when the eyes are within the arrival radius of the end (capped at V5's 1.9); V5's "last quarter
            // segment" rule finished too early on long straights
            int last = pts.size() - 1;
            Vec3 lastPoint = pts.get(last);
            double completion = Math.min(1.9, arrivalRadius);
            if (pos >= last - 0.25) pos = last;
            if (eyes.distanceToSqr(lastPoint) <= completion * completion && pos >= last - 2.0) {
                pos = last;
                complete = true;
            }
            return new float[]{yaw, pitch, holdYaw ? 1 : 0};
        }

        /** Where the drop just ahead lands: the first path point after {@code pos} more than 0.8 below it, or null. */
        Vec3 dropLanding() {
            if (pts == null) return null;
            Vec3 cur = at(pos);
            for (int i = (int) Math.floor(pos) + 1; i < pts.size() && i <= pos + 4; i++) {
                if (cur.y - pts.get(i).y > 0.8) return pts.get(i);
            }
            return null;
        }

        boolean pathDropping() {
            if (pts == null || pos >= pts.size() - 2) return false;
            Vec3 cur = at(pos), ahead = at(Math.min(pts.size() - 1, pos + 2));
            return cur.y - ahead.y > 0.8;
        }

        void applyPredictedProgress(LocalPlayer player) {
            double speed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
            boolean ground = player.onGround();
            if (ground && speed < PREDICTION_MIN_SPEED_XZ) return;
            double[] predicted = ground ? new double[]{player.getX(), player.getZ()} : predictXZ(player, PREDICTION_TICKS);
            double projected = projectHorizontal(predicted[0], predicted[1], pos);
            if (!Double.isFinite(projected) || projected <= pos) return;
            double maxAdvance = ground ? PREDICTION_MAX_ADVANCE_GROUND : PREDICTION_MAX_ADVANCE_AIR;
            pos = Math.min(pos + maxAdvance, projected);
        }

        double projectHorizontal(double x, double z, double hint) {
            if (pts == null || pts.size() < 2) return 0;
            int lastSegment = pts.size() - 2;
            int base = Math.max(0, Math.min(lastSegment, (int) Math.floor(hint)));
            int start = Math.max(0, base - 8), end = Math.min(lastSegment, base + 28);
            double bestT = Math.max(0, Math.min(pts.size() - 1, hint)), bestDistSq = Double.MAX_VALUE;
            for (int i = start; i <= end; i++) {
                Vec3 a = pts.get(i), b = pts.get(i + 1);
                double abx = b.x - a.x, abz = b.z - a.z, lenSq = abx * abx + abz * abz;
                double t = lenSq <= 1e-8 ? 0 : Math.max(0, Math.min(1, ((x - a.x) * abx + (z - a.z) * abz) / lenSq));
                double px = a.x + abx * t, pz = a.z + abz * t;
                double d = sq(x - px) + sq(z - pz);
                if (d < bestDistSq) {
                    bestDistSq = d;
                    bestT = i + t;
                }
            }
            return bestT;
        }

        double adaptiveLookahead(Vec3 eyes, double ts) {
            if (lookaheadOverride != null && lookaheadOverrideTicks > 0) return lookaheadOverride;
            int target = (int) Math.floor(pos);
            if (target + 3 >= pts.size()) return smoothedLookahead;
            Vec3 pathPoint = at(pos);
            double deviation = Math.hypot(eyes.x - pathPoint.x, eyes.z - pathPoint.z);
            double deviationFactor = Math.min(1, Math.max(0, (deviation - 1.6) / 2.0));
            Vec3 s = pts.get(target), e = pts.get(Math.min(target + 2, pts.size() - 1));
            Vec3 startDir = e.subtract(s);
            double startLen = startDir.length();
            double maxAngle = 0;
            for (int k = 4; k <= 8; k += 2) {
                int f = Math.min(target + k, pts.size() - 3);
                if (f <= target + 2) continue;
                Vec3 fa = pts.get(f), fb = pts.get(Math.min(f + 2, pts.size() - 1));
                Vec3 futureDir = fb.subtract(fa);
                double futureLen = futureDir.length();
                if (startLen > 0.8 && futureLen > 0.8) {
                    double dot = startDir.dot(futureDir) / (startLen * futureLen);
                    maxAngle = Math.max(maxAngle, Math.acos(Math.max(-1, Math.min(1, dot))));
                }
            }
            curvature = maxAngle;
            if (mc.player != null && mc.player.getDeltaMovement().y < -0.1) maxAngle *= 0.5;
            double curveFactor = Math.min(1, Math.max(0, (maxAngle - 0.61) / 0.7));
            double adjust = Math.max(deviationFactor, curveFactor);
            double targetDistance = MAX_LOOKAHEAD - (MAX_LOOKAHEAD - MIN_LOOKAHEAD) * adjust;
            double lerp = targetDistance > smoothedLookahead ? 0.1 : 0.05;
            lerp = 1 - Math.pow(1 - lerp, ts);
            smoothedLookahead += (targetDistance - smoothedLookahead) * lerp;
            return smoothedLookahead;
        }

        /** {point, lookahead}: the farthest visible point up to {@code ideal} along the line that needs no odd turn. */
        Object[] findVisibleLookahead(Vec3 eyes, double ideal) {
            Vec3 immediate = at(Math.min(pts.size() - 1, pos + 0.5));
            Vec3 vecImmediate = immediate.subtract(eyes);
            boolean recovery = recoveryMode();
            double effectiveMin = recovery ? RECOVERY_MIN_LOOKAHEAD : MIN_LOOKAHEAD;
            double lookahead = recovery ? ideal : Math.max(ideal, MIN_LOOKAHEAD);
            while (lookahead >= effectiveMin) {
                double t = Math.min(pts.size() - 1, pos + lookahead);
                Vec3 point = at(t);
                double dx = point.x - eyes.x, dy = point.y - eyes.y, dz = point.z - eyes.z;
                double horz = Math.hypot(dx, dz);
                if (lookahead >= MIN_LOOKAHEAD) {
                    if (dy > 1.8 && horz < 0.8) {
                        lookahead -= LOOKAHEAD_STEP;
                        continue;
                    }
                    double pitch = -Math.toDegrees(Math.atan2(dy, horz));
                    if (pitch < MAX_UPWARD_PITCH && horz < 1.5) {
                        lookahead -= LOOKAHEAD_STEP;
                        continue;
                    }
                    double bend = angleBetween(vecImmediate, new Vec3(dx, dy, dz));
                    // A bend with an open inside (floor all the way and nothing to hit) is turned onto sooner,
                    // cutting across it. Round a real corner, with a wall on the inside, the look stays pulled in
                    // and it turns later, at the corner.
                    if (bend > MAX_DIRECTION_DIVERGENCE && !(bend <= OPEN_BEND_DIVERGENCE && openBend(point))) {
                        bendFails++;
                        lookahead -= LOOKAHEAD_STEP;
                        continue;
                    }
                }
                // visible from the eyes and clear for the whole body (a line from the eyes
                // can pass a corner the body catches on)
                boolean seen = visible(eyes, point, true);
                if (!seen) unseenFails++;
                if (seen && (lookahead < MIN_LOOKAHEAD || bodyClear(point))) {
                    lastLookahead = lookahead;
                    return new Object[]{point, lookahead};
                }
                if (seen) bodyFails++;
                lookahead -= lookahead < MIN_LOOKAHEAD ? RECOVERY_LOOKAHEAD_STEP : LOOKAHEAD_STEP;
            }
            double close = Math.max(RECOVERY_MIN_LOOKAHEAD, MIN_LOOKAHEAD - 0.2);
            while (close >= RECOVERY_MIN_LOOKAHEAD) {
                Vec3 point = at(Math.min(pts.size() - 1, pos + close));
                if (visible(eyes, point, true)) return new Object[]{point, close};
                close -= RECOVERY_LOOKAHEAD_STEP;
            }
            return new Object[]{at(Math.min(pts.size() - 1, pos + effectiveMin)), effectiveMin};
        }

        Vec3 horizontallyDistantPoint(Vec3 eyes) {
            return horizontallyDistantPoint(eyes, MIN_HORIZONTAL_LOOK + 0.3);
        }

        /**
         * True if walking straight to {@code point} won't run the (slightly widened) body into anything. Only
         * checked on the ground; always true in the air.
         */
        boolean bodyClear(Vec3 point) {
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || !p.onGround()) return true;
            double dx = point.x - p.getX(), dz = point.z - p.getZ(), len = Math.hypot(dx, dz);
            if (len < 0.3) return true;
            // a little room to spare, so a gap the body only just fits gets a closer look point instead
            net.minecraft.world.phys.AABB box = p.getBoundingBox().inflate(0.12, 0, 0.12);
            box = new net.minecraft.world.phys.AABB(box.minX, box.minY + 0.6, box.minZ, box.maxX, box.maxY, box.maxZ);
            // rise with the path toward the look point's floor; kept level, the ground ahead on a slope
            // counted as in the way and the view swayed
            double rise = point.y - 1.62 - p.getY();
            // ...and more room around the head, so cutting a corner doesn't catch it under a low block
            net.minecraft.world.phys.AABB head = p.getBoundingBox().inflate(0.3, 0, 0.3);
            head = new net.minecraft.world.phys.AABB(head.minX, head.minY + 1.3, head.minZ, head.maxX, head.maxY, head.maxZ);
            int steps = (int) Math.ceil(len / 0.3);
            for (int i = 1; i <= steps; i++) {
                double f = (double) i / steps;
                if (!mc.level.noCollision(p, box.move(dx * f, Math.max(0, rise * f), dz * f))) return false;
                if (!mc.level.noCollision(p, head.move(dx * f, Math.max(0, rise * f), dz * f))) return false;
            }
            return true;
        }

        /** The first point along the path (going on) at least {@code minAcross} away horizontally, or null. */
        Vec3 horizontallyDistantPoint(Vec3 eyes, double minAcross) {
            int last = pts.size() - 1;
            double start = Math.max(pos, projectHorizontal(eyes.x, eyes.z, pos));
            Vec3 anchor = at(start);
            for (double t = start; t <= Math.min(last, start + 14); t += 0.5) {
                Vec3 p = at(t);
                double dx = p.x - eyes.x, dz = p.z - eyes.z;
                if (Math.hypot(dx, dz) < minAcross) continue;
                if (dx * (p.x - anchor.x) + dz * (p.z - anchor.z) > 0) return p;
            }
            return null;
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double closest3d(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a);
        double lenSq = d.lengthSqr();
        if (lenSq == 0) return 0;
        return Math.max(0, Math.min(1, p.subtract(a).dot(d) / lenSq));
    }

    private static double closestHorizontal(Vec3 p, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z, lenSq = dx * dx + dz * dz;
        if (lenSq == 0) return 0;
        return Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.z - a.z) * dz) / lenSq));
    }

    private static double angleBetween(Vec3 a, Vec3 b) {
        double la = a.length(), lb = b.length();
        if (la < 0.001 || lb < 0.001) return 0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.dot(b) / (la * lb)))));
    }

    /** Where you'll be in {@code ticks} ticks if you keep moving as you are through the air (V5's PathPrediction). */
    private static double[] predictXZ(LocalPlayer p, int ticks) {
        double px = p.getX(), pz = p.getZ();
        Vec3 v = p.getDeltaMovement();
        double vx = v.x, vy = v.y, vz = v.z;
        for (int i = 0; i < Math.max(1, ticks); i++) {
            px += vx;
            pz += vz;
            vy += -0.08;
            vx *= 0.91;
            vz *= 0.91;
            vy *= 0.98;
            if (Math.abs(vx) < 0.002 && Math.abs(vz) < 0.002 && Math.abs(vy) < 0.002) break;
        }
        return new double[]{px, pz};
    }

    /**
     * Whether {@code to} is visible from {@code eyes}: no block with a collision box in the way (a voxel walk). With
     * {@code endpointOk}, a block hit within half a block of the end doesn't count.
     */
    static boolean visible(Vec3 eyes, Vec3 to, boolean endpointOk) {
        Level level = mc.level;
        if (level == null || to == null) return true;
        double dx = to.x - eyes.x, dy = to.y - eyes.y, dz = to.z - eyes.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.2) return true;
        double len = dist + 0.1;
        double ex = eyes.x + dx / dist * len, ey = eyes.y + dy / dist * len, ez = eyes.z + dz / dist * len;
        BlockPos hit = traverse(level, eyes.x, eyes.y, eyes.z, ex, ey, ez);
        if (hit == null || !endpointOk) return hit == null;
        double hd = Math.sqrt(sq(hit.getX() + 0.5 - eyes.x) + sq(hit.getY() + 0.5 - eyes.y) + sq(hit.getZ() + 0.5 - eyes.z));
        return hd >= dist - 0.5;
    }

    /** The first block with a collision box on the straight line from s to e (BloomCore's traverseVoxels). */
    private static BlockPos traverse(Level level, double sx, double sy, double sz, double ex, double ey, double ez) {
        double[] start = {sx, sy, sz}, dir = {ex - sx, ey - sy, ez - sz};
        int[] step = new int[3], cur = new int[3], end = new int[3];
        double[] tDelta = new double[3], tMax = new double[3];
        for (int i = 0; i < 3; i++) {
            step[i] = (int) Math.signum(dir[i]);
            cur[i] = (int) Math.floor(start[i]);
            end[i] = (int) Math.floor(i == 0 ? ex : i == 1 ? ey : ez);
            tDelta[i] = dir[i] == 0 ? Double.MAX_VALUE : Math.abs(1 / dir[i]);
            if (tDelta[i] == Double.MAX_VALUE) tMax[i] = Double.MAX_VALUE;
            else tMax[i] = (step[i] > 0 ? cur[i] + 1 - start[i] : start[i] - cur[i]) * tDelta[i];
        }
        int maxIters = (int) Math.ceil(Math.abs(dir[0]) + Math.abs(dir[1]) + Math.abs(dir[2])) + 10;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int it = 0; it < maxIters && it < 1000; it++) {
            m.set(cur[0], cur[1], cur[2]);
            try {
                BlockState st = level.getBlockState(m);
                if (!st.isAir() && !st.getCollisionShape(level, m).isEmpty()) return m.immutable();
            } catch (Exception e) {
                return m.immutable();
            }
            if (cur[0] == end[0] && cur[1] == end[1] && cur[2] == end[2]) return null;
            int axis = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
            cur[axis] += step[axis];
            tMax[axis] += tDelta[axis];
        }
        return null;
    }

    // ═════════════════════════ AOTV (PathAote): not used in this mod, kept from the Java mod ═════════════════════════

    private static final Aotv aotv = new Aotv();

    /**
     * Casts the AOTV's plain right-click teleport along the way, like V5's PathAote. Only on a straight, clear stretch
     * ahead (see {@link #RANGE}, {@link #MIN_GAIN}, {@link #END_MARGIN}, {@link #MAX_DEVIATION}) that you're already
     * facing within {@link #FACING}° (turning for a cast walked it into walls), and only if the cast lands by the path.
     * Never while sneaking (that would be an etherwarp). After two casts that don't go (no mana) it waits 10 s.
     * Switched off in this mod: {@link #tick} always returns early.
     */
    private static final class Aotv {
        // casts of at least 10 blocks, as in V5; shorter hops weren't worth it
        static final double RANGE = 11.5, MIN_GAIN = 10, END_MARGIN = 5, MIN_REMAINING = 15, MAX_DEVIATION = 1.2;
        static final float FACING = 12f;
        static final double STRAIGHT_AFTER = 3.0, BEND_AFTER = 50;
        static final int COOLDOWN = 8, LAND_TIMEOUT = 10, GAVE_UP_TICKS = 200;
        int cooldown, releaseTicks, awaitTicks, misses;
        double aimAt;
        Vec3 firedFrom;
        int originalSlot = -1;

        void reset() {
            firedFrom = null;
            if (releaseTicks > 0) mc.options.keyUse.setDown(false);
            releaseTicks = 0;
            cooldown = 0;
            restoreSlot();
        }

        void restoreSlot() {
            if (originalSlot >= 0 && mc.player != null) mc.player.getInventory().setSelectedSlot(originalSlot);
            originalSlot = -1;
        }

        /**
         * Detects that a cast went and picks the path up where you landed. Also runs every frame, because the teleport
         * packet arrives between frames and until the next tick the look point would still be behind you.
         */
        void checkLanded(LocalPlayer p) {
            if (firedFrom == null || p.position().distanceTo(firedFrom) <= 4.0) return;
            firedFrom = null;
            misses = 0;
            rot.onTeleport(aimAt);
            jumps.invalidateNearest();
            recovery.resetTracking();
            landedThisTick = true;
        }

        boolean landedThisTick;

        void tick(LocalPlayer p) {
            if (releaseTicks > 0 && --releaseTicks == 0) mc.options.keyUse.setDown(false);
            checkLanded(p);
            if (landedThisTick) {
                landedThisTick = false;
                return;
            }
            if (firedFrom != null) {
                if (++awaitTicks > LAND_TIMEOUT) { // didn't go (no mana, or blocked)
                    firedFrom = null;
                    if (++misses >= 2) {
                        misses = 0;
                        cooldown = GAVE_UP_TICKS;
                    }
                }
                return;
            }
            if (cooldown > 0) {
                cooldown--;
                return;
            }
            if (true /* Teleporting is outside this standalone mod's scope. */ || shiftWalk || recovering()
                    || false
                    || rot.pts == null || p.isShiftKeyDown() || (!p.onGround() && Math.abs(p.getDeltaMovement().y) > 0.25)) {
                return;
            }
            int slot = slot();
            if (slot < 0) {
                return;
            }
            double[] found = target(p);
            if (found == null) return;
            Vec3 to = rot.at(found[0]);
            Vec3 eye = p.getEyePosition();
            float yawTo = (float) Math.toDegrees(Math.atan2(-(to.x - eye.x), to.z - eye.z));
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yawTo - p.getYRot())) > FACING) return; // not facing it
            double cast = castDistance(p);
            if (cast < MIN_GAIN) return; // a cast where you're looking stops short (a wall, the floor)
            if (!landsByPath(eye.add(p.getViewVector(1.0f).scale(cast)), found[0])) return;
            if (p.getInventory().getSelectedSlot() != slot) {
                if (originalSlot < 0) originalSlot = p.getInventory().getSelectedSlot();
                p.getInventory().setSelectedSlot(slot);
                return; // swapped: cast next tick
            }
            aimAt = found[0];
            mc.options.keyUse.setDown(true);
            releaseTicks = 2;
            firedFrom = p.position();
            awaitTicks = 0;
            cooldown = COOLDOWN;
            jumps.suppress(5);
            return;
        }

        /** True if a cast landing at {@code at} is within 1.5 horizontally of the path up to {@code upTo}. */
        boolean landsByPath(Vec3 at, double upTo) {
            int last = rot.pts.size() - 1;
            for (double t = rot.pos; t <= Math.min(last, upTo + 2); t += 0.25) {
                Vec3 q = rot.at(t);
                if (Math.hypot(q.x - at.x, q.z - at.z) <= 1.5 && Math.abs(q.y - at.y) <= 2.0) return true;
            }
            return false;
        }

        /** The look-point position of the farthest point a cast can be aimed at (as a one-element array), or null. */
        double[] target(LocalPlayer p) {
            List<Vec3> pts = rot.pts;
            int last = pts.size() - 1;
            double total = 0;
            for (int i = (int) Math.floor(rot.pos); i < last; i++) total += pts.get(i).distanceTo(pts.get(i + 1));
            if (total < MIN_REMAINING) return null;
            Vec3 eye = p.getEyePosition();
            Vec3 feet = p.position();
            double best = -1, walked = 0;
            Vec3 prev = rot.at(rot.pos);
            for (double t = rot.pos + 0.25; t <= last; t += 0.25) {
                Vec3 q = rot.at(t);
                walked += prev.distanceTo(q);
                prev = q;
                if (q.distanceTo(eye) > RANGE) break;
                if (walked < MIN_GAIN) continue;
                if (total - walked < END_MARGIN) break;
                if (!straight(eye, q, t) || !clear(eye, q) || !clear(feet.add(0, 0.3, 0), q.add(0, 0.3 - (eye.y - feet.y), 0))) continue;
                if (bendsAfter(eye, q, t)) continue;
                best = t;
            }
            return best < 0 ? null : new double[]{best};
        }

        /**
         * True if the path turns more than {@link #BEND_AFTER}° within {@link #STRAIGHT_AFTER} blocks of where the cast
         * lands. Casts are for straights; turns are walked.
         */
        boolean bendsAfter(Vec3 eye, Vec3 q, double t) {
            double cx = q.x - eye.x, cz = q.z - eye.z, cl = Math.hypot(cx, cz);
            if (cl < 0.5) return false;
            double walked = 0;
            Vec3 prev = q;
            for (double u = t + 0.25; u <= rot.pts.size() - 1 && walked < STRAIGHT_AFTER; u += 0.25) {
                Vec3 m = rot.at(u);
                walked += prev.distanceTo(m);
                prev = m;
                double dx = m.x - q.x, dz = m.z - q.z, dl = Math.hypot(dx, dz);
                if (dl < 1.0) continue;
                double cos = (cx * dx + cz * dz) / (cl * dl);
                if (cos < Math.cos(Math.toRadians(BEND_AFTER))) return true;
            }
            return false;
        }

        /** True if the path up to look-point position {@code t} stays within {@link #MAX_DEVIATION} of the line. */
        boolean straight(Vec3 eye, Vec3 q, double t) {
            double lx = q.x - eye.x, lz = q.z - eye.z, len = Math.hypot(lx, lz);
            if (len < 0.5) return false;
            lx /= len;
            lz /= len;
            for (double u = rot.pos; u < t; u += 0.5) {
                Vec3 m = rot.at(u);
                double ox = m.x - eye.x, oz = m.z - eye.z;
                if (Math.abs(ox * lz - oz * lx) > MAX_DEVIATION) return false;
            }
            return true;
        }

        boolean clear(Vec3 from, Vec3 to) {
            return mc.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player)).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
        }

        /** How far a cast would go as you look now (V5's estimate: to the first block the body can't pass). */
        double castDistance(LocalPlayer p) {
            Vec3 start = p.getEyePosition();
            Vec3 dir = p.getViewVector(1.0f);
            for (int off = 1; off <= 12; off++) {
                BlockPos b = BlockPos.containing(start.add(dir.scale(off)));
                if (!through(b) || !through(b.above())) return off - 1;
            }
            return 12;
        }

        boolean through(BlockPos b) {
            BlockState s = mc.level.getBlockState(b);
            if (s.isAir() || s.getCollisionShape(mc.level, b).isEmpty()) return true;
            if (s.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock) return true;
            return s.getBlock() instanceof SnowLayerBlock && s.getValue(SnowLayerBlock.LAYERS) <= 3;
        }

        /** The hotbar slot holding an AOTV or AOTE, or -1 if there's none. */
        int slot() {
            for (int i = 0; i < 9; i++) {
                String n = mc.player.getInventory().getItem(i).getHoverName().getString().toLowerCase();
                if (n.contains("aspect of the void") || n.contains("aspect of the end") || n.contains("aotv")) return i;
            }
            return -1;
        }
    }

    // ═══════════════════════════════════ jumps (PathJumps) ═══════════════════════════════════

    private static final Jumps jumps = new Jumps();

    private static final class Jumps {
        static final double STEP_HEIGHT = 0.6, PREEMPTIVE_JUMP_DISTANCE = 1.65;
        static final int LOOKAHEAD_NODES = 3, NEAREST_SEARCH_WINDOW = 16;
        int suppressTicks;
        List<BlockPos> lastNearestPath;
        int lastNearestIndex = -1;

        record Ahead(BlockPos pos, BlockState state, String name) {}

        void tick() {
            if (suppressTicks > 0) {
                suppressTicks--;
                mc.options.keyJump.setDown(false);
            }
        }

        void suppress(int ticks) {
            suppressTicks = Math.max(suppressTicks, ticks);
        }

        void invalidateNearest() {
            lastNearestPath = null;
            lastNearestIndex = -1;
        }

        void reset() {
            suppressTicks = 0;
            invalidateNearest();
            mc.options.keyJump.setDown(false);
        }

        private static String name(BlockState s) {
            return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        }

        private static boolean collides(Level level, BlockPos p) {
            BlockState s = level.getBlockState(p);
            return !s.getCollisionShape(level, p).isEmpty();
        }

        /** Index of the nearest node; adds the solid floor blocks of the next 3 nodes to {@code ahead}. */
        int nearest(LocalPlayer p, List<BlockPos> nodes, List<Ahead> ahead) {
            double px = p.getX(), py = p.getY(), pz = p.getZ();
            int closest = -1;
            double min = Double.MAX_VALUE;
            boolean local = nodes == lastNearestPath && lastNearestIndex >= 0 && lastNearestIndex < nodes.size();
            int start = local ? Math.max(0, lastNearestIndex - NEAREST_SEARCH_WINDOW) : 0;
            int end = local ? Math.min(nodes.size(), lastNearestIndex + NEAREST_SEARCH_WINDOW + 1) : nodes.size();
            for (int i = start; i < end; i++) {
                BlockPos n = nodes.get(i);
                double d = sq(px - (n.getX() + 0.5)) + sq(py - (n.getY() - 1 + 0.5)) + sq(pz - (n.getZ() + 0.5));
                if (d < min) {
                    min = d;
                    closest = i;
                }
            }
            if (closest == -1) return -1;
            lastNearestPath = nodes;
            lastNearestIndex = closest;
            Level level = mc.level;
            for (int i = closest + 1; i < Math.min(closest + 1 + LOOKAHEAD_NODES, nodes.size()); i++) {
                BlockPos floor = nodes.get(i).below();
                if (collides(level, floor)) {
                    BlockState s = level.getBlockState(floor);
                    ahead.add(new Ahead(floor, s, name(s)));
                }
            }
            return closest;
        }

        /**
         * V5's jump checks for this tick: swim up in fluids, never jump under a low ceiling, otherwise jump for a
         * step up, a gap, deep snow or an obstacle.
         */
        void detect(LocalPlayer p) {
            if (suppressTicks > 0) {
                mc.options.keyJump.setDown(false);
                return;
            }
            List<Ahead> ahead = new ArrayList<>();
            int closest = nearest(p, path, ahead);
            if (closest == -1) {
                reset();
                return;
            }
            int nextFlags = pathFlags != null && pathFlags.length > 0
                    ? pathFlags[Math.min(pathFlags.length - 1, Math.max(0, closest + 1))] : 0;
            if ((nextFlags & V5Search.FLAG_LOW_HEADROOM) != 0) {
                suppress(4);
                mc.options.keyJump.setDown(false);
                return;
            }
            if (p.isInWater() || p.isInLava()) {
                mc.options.keyJump.setDown(true);
                return;
            }
            if (!p.onGround()) {
                if (!recovering()) mc.options.keyJump.setDown(false);
                return;
            }
            int floorY = (int) Math.floor(p.getY() - 0.001);
            if (lowCeiling((int) Math.floor(p.getX()), floorY, (int) Math.floor(p.getZ()))) {
                reset();
                return;
            }
            if ((nextFlags & V5Search.FLAG_STEP_UP_NEXT) != 0 && preemptiveClimb(p, closest)) return;
            if (gap(p, closest)) return;
            if (snow(p, ahead)) return;
            if (obstacle(p, ahead, floorY)) return;
            if (!recovering()) mc.options.keyJump.setDown(false);
        }

        private boolean lowCeiling(int x, int y, int z) {
            Level level = mc.level;
            for (int off = 2; off <= 3; off++) {
                BlockPos b = new BlockPos(x, y + off, z);
                BlockState s = level.getBlockState(b);
                if (s.isAir() || name(s).contains("stair")) continue;
                if (!s.getCollisionShape(level, b).isEmpty()) return true;
            }
            return false;
        }

        private boolean snow(LocalPlayer p, List<Ahead> ahead) {
            if (ahead.isEmpty()) return false;
            Ahead a = ahead.get(0);
            if (!a.name.equals("minecraft:snow") || !(a.state.getBlock() instanceof SnowLayerBlock)) return false;
            int layers = a.state.getValue(SnowLayerBlock.LAYERS);
            double diff = a.pos.getY() - (8 - layers) * 0.125 - (p.getY() - 1);
            if (diff > 0.75 && layers > 6) {
                mc.options.keyJump.setDown(true);
                return true;
            }
            return false;
        }

        private boolean obstacle(LocalPlayer p, List<Ahead> ahead, int floorY) {
            if (ahead.isEmpty()) return false;
            BlockState under = mc.level.getBlockState(new BlockPos((int) Math.floor(p.getX()), floorY, (int) Math.floor(p.getZ())));
            String underName = name(under);
            double limit = underName.contains("stair") || underName.contains("slab") ? 1.05 : STEP_HEIGHT;
            boolean needsJump = false, canWalk = false;
            for (Ahead a : ahead) {
                if (a.name.contains("snow")) continue;
                if (a.pos.getY() - floorY > limit) needsJump = true;
                if (a.name.contains("slab")) canWalk = true;
                else if (a.name.contains("stair") && canWalkUpStairs(p, a)) canWalk = true;
            }
            if (needsJump && !canWalk) {
                mc.options.keyJump.setDown(true);
                return true;
            }
            return false;
        }

        private boolean canWalkUpStairs(LocalPlayer p, Ahead a) {
            if (!(a.state.getBlock() instanceof StairBlock)) return true;
            Direction facing = a.state.getValue(StairBlock.FACING);
            double dx = a.pos.getX() + 0.5 - p.getX(), dz = a.pos.getZ() + 0.5 - p.getZ();
            Direction approach = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.WEST : Direction.EAST)
                    : (dz > 0 ? Direction.NORTH : Direction.SOUTH);
            return approach == facing.getOpposite();
        }

        private boolean preemptiveClimb(LocalPlayer p, int closest) {
            if (closest + 1 >= path.size()) return false;
            BlockPos cur = path.get(closest), next = path.get(closest + 1);
            if (next.getY() <= cur.getY()) return false;
            double dx = next.getX() + 0.5 - p.getX(), dz = next.getZ() + 0.5 - p.getZ();
            if (dx * dx + dz * dz > PREEMPTIVE_JUMP_DISTANCE * PREEMPTIVE_JUMP_DISTANCE) return false;
            // use the real height: V5 counts a snow layer of 2+ (or a slab) as a full
            // block, so thin snow looked like a step up
            double rise = supportTop(next) - p.getY();
            if (rise <= STEP_HEIGHT || rise > V5Search.maxClimb + 1.2 || entryRise(p, next) <= STEP_HEIGHT) return false;
            mc.options.keyJump.setDown(true);
            return true;
        }

        /** Feet height at a path node: the real top of what's under it (snow layer, slab). */
        double supportTop(BlockPos node) {
            Level level = mc.level;
            BlockPos under = node.below();
            var shape = level.getBlockState(under).getCollisionShape(level, under);
            if (!shape.isEmpty()) return under.getY() + shape.max(Direction.Axis.Y);
            var in = level.getBlockState(node).getCollisionShape(level, node);
            if (!in.isEmpty()) return node.getY() + in.max(Direction.Axis.Y);
            BlockPos under2 = under.below();
            var shape2 = level.getBlockState(under2).getCollisionShape(level, under2);
            return shape2.isEmpty() ? node.getY() : under2.getY() + shape2.max(Direction.Axis.Y);
        }

        private boolean gap(LocalPlayer p, int closest) {
            if (closest == -1 || path.size() < closest + 3) return false;
            // use the real standing heights, or a path over patchy snow looks like a row of gaps to jump
            double base = supportTop(path.get(closest));
            if (Math.abs(p.getY() - base) > 1.8) return false;
            int dropIndex = -1, recoveryIndex = -1;
            for (int i = closest + 1; i < Math.min(path.size(), closest + 7); i++) {
                double y = supportTop(path.get(i));
                if (y < base - 0.6) {
                    if (dropIndex == -1) dropIndex = i;
                } else if (dropIndex != -1) {
                    recoveryIndex = i;
                    break;
                } else if (y > base + 0.6) {
                    return false;
                }
            }
            if (dropIndex != -1 && recoveryIndex != -1) {
                BlockPos edge = path.get(dropIndex - 1), land = path.get(recoveryIndex);
                double gapSq = sq(land.getX() - edge.getX()) + sq(land.getZ() - edge.getZ());
                if (gapSq > 0 && gapSq <= 20) {
                    double toEdge = sq(p.getX() - (edge.getX() + 0.5)) + sq(p.getZ() - (edge.getZ() + 0.5));
                    if (toEdge <= 1.35 * 1.35) {
                        // only if the jump lands on the path past the gap; with a strong jump boost, a hop
                        // can fly off the side of a walkway
                        if (!jumpLandsOnWay(p, land)) return false;
                        mc.options.keyJump.setDown(true);
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // ═══════════════════════════════════ recovery (PathRecovery) ═══════════════════════════════════

    private static final Recovery recovery = new Recovery();
    private static final NonChange nonChange = new NonChange();

    /** V5's stuck detection: stuck on the ground for 10/22/44 ticks gives JUMP, CLOSE_LOOK, BACKUP_RECALC. */
    private static final class Recovery {
        static final double MOVING_SQ = 0.12 * 0.12, PROGRESS_SQ = 3.0 * 3.0;
        static final int STUCK_JUMP = 10, STUCK_CLOSE_LOOK = 22, STUCK_BACKUP_RECALC = 44;
        double[] lastPos, stuckPos;
        int stuckTicks, level;

        String track(LocalPlayer p) {
            if (p.isInWater() || p.isInLava()) {
                resetTracking();
                return null;
            }
            double px = p.getX(), pz = p.getZ();
            double moved = 1.0;
            if (lastPos != null) moved = sq(px - lastPos[0]) + sq(pz - lastPos[1]);
            if (moved > MOVING_SQ) {
                resetTracking();
                lastPos = new double[]{px, pz};
                return null;
            }
            if (stuckTicks == 0) stuckPos = new double[]{px, pz};
            if (!p.onGround()) {
                lastPos = new double[]{px, pz};
                return null;
            }
            stuckTicks++;
            lastPos = new double[]{px, pz};
            if (stuckTicks >= STUCK_BACKUP_RECALC && level < 3) {
                level = 3;
                return "BACKUP_RECALC";
            }
            if (stuckTicks >= STUCK_CLOSE_LOOK && level < 2) {
                level = 2;
                return "CLOSE_LOOK";
            }
            if (stuckTicks >= STUCK_JUMP && level < 1) {
                level = 1;
                return "JUMP";
            }
            return null;
        }

        boolean hasMadeProgress(LocalPlayer p) {
            if (stuckPos == null) return false;
            return sq(p.getX() - stuckPos[0]) + sq(p.getZ() - stuckPos[1]) > PROGRESS_SQ;
        }

        boolean stallActive() {
            return level > 0;
        }

        void resetTracking() {
            stuckTicks = 0;
            level = 0;
            stuckPos = null;
        }

        void stop() {
            resetTracking();
            lastPos = null;
        }
    }

    /** Asks for a re-search after 35 ticks on the ground without getting at least 0.45 further along the path. */
    private static final class NonChange {
        static final double PROGRESS_DELTA = 0.45;
        static final int TICKS_RECALC = 35;
        Double best;
        int ticks;

        boolean wasAir;

        boolean track(LocalPlayer p, double pathPosition) {
            if (p.isInWater() || p.isInLava() || !Double.isFinite(pathPosition)) {
                resetTracking();
                return false;
            }
            // time in the air (a drop, a long pre-jump) doesn't count as no headway; it used to re-search mid-fall
            if (!p.onGround()) {
                wasAir = true;
                return false;
            }
            if (wasAir) {
                wasAir = false;
                resetTracking();
            }
            if (best == null || pathPosition > best + PROGRESS_DELTA) {
                best = pathPosition;
                ticks = 0;
                return false;
            }
            if (++ticks >= TICKS_RECALC) {
                resetTracking();
                return true;
            }
            return false;
        }

        void resetTracking() {
            best = null;
            ticks = 0;
        }

        void stop() {
            resetTracking();
        }
    }
}
