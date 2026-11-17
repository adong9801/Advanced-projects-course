package com.rustpath.client.pathfinder;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sends a search to the Rust library and reads the route back. The search itself is in
 * native/pathfinder/src/v5; this class only packs the request into an int array and unpacks the answer.
 */
public final class V5Search {
    private V5Search() {}

    public static final int MAX_ITERATIONS = 500_000;

    // Flags Rust puts on each block of the route (node_flags in v5/search.rs)
    public static final int FLAG_LOW_HEADROOM = 4;
    public static final int FLAG_STEP_UP_NEXT = 32;

    /** How many blocks one climb can go up. 1 is a normal jump. */
    public static volatile int maxClimb = 1;

    // The walker from my Java pathfinder mod still refers to these, but nothing in this mod fills them in.
    public static final List<double[]> likedEdges = List.of();
    public static final Set<Long> failedEdges = new HashSet<>();

    public interface Dig {
        float cost(int x, int y, int z);

        default void failed(int x, int y, int z) {}
    }

    public record AvoidZone(int x, int y, int z, int radiusSq, int maxYDiff, float penalty) {}

    public record Result(List<BlockPos> path, int[] flags, int startIndex, int explored, long ms, long signature) {}

    public static long edgeKey(double[] edge) {
        long key = 17;
        for (int i = 0; i < 6; i++) key = key * 31 + (long) edge[i];
        return key;
    }

    /** Returns null when there's no route (or the deadline has already passed). */
    public static Result find(V5World world, List<BlockPos> starts, Collection<BlockPos> goals, int limit,
                              int seed, List<AvoidZone> avoid, long deadline) {
        if (!world.captured()) {
            throw new IllegalStateException("Capture Minecraft blocks on the client thread before searching.");
        }
        long began = System.nanoTime();
        if (deadline > 0 && System.currentTimeMillis() > deadline) return null;
        if (avoid == null) avoid = List.of();

        int[] request = buildRequest(world, starts, goals, limit, seed, avoid, deadline);
        int[] answer = NativePathfinder.nativeFindV5(world.nativeCells, world.nativeCorners, request);

        // answer = status, explored, length, startIndex, signature (2 ints), then x, y, z, flags per block
        if (answer == null || answer.length < 6 || answer[0] != 1) return null;
        int length = answer[2];
        if (length < 0 || 6L + length * 4L != answer.length) throw new IllegalStateException("Malformed Rust route");

        List<BlockPos> path = new ArrayList<>(length);
        int[] flags = new int[length];
        for (int k = 0; k < length; k++) {
            int i = 6 + 4 * k;
            path.add(new BlockPos(answer[i], answer[i + 1], answer[i + 2]));
            flags[k] = answer[i + 3];
        }
        long signature = (answer[4] & 0xffffffffL) | ((long) answer[5] << 32);
        long ms = (System.nanoTime() - began) / 1_000_000;
        return new Result(List.copyOf(path), flags, answer[3], answer[1], ms, signature);
    }

    /**
     * The request layout the Rust side reads (read_v5_request in jni.rs): a 16-int header, then the starts and
     * goals as x, y, z, then each avoid zone as x, y, z, radiusSq, maxY and the penalty's float bits.
     */
    private static int[] buildRequest(V5World world, List<BlockPos> starts, Collection<BlockPos> goals, int limit,
                                      int seed, List<AvoidZone> avoid, long deadline) {
        int timeoutMs = deadline <= 0
                ? 5000
                : (int) Math.max(1, Math.min(5000, deadline - System.currentTimeMillis()));
        int[] header = {
                1, // version
                world.cx0, world.cy0, world.cz0,
                world.sx, world.sy, world.sz,
                world.minY, world.maxY,
                limit, seed, maxClimb, timeoutMs,
                starts.size(), goals.size(), avoid.size()
        };

        int[] request = new int[header.length + 3 * (starts.size() + goals.size()) + 6 * avoid.size()];
        System.arraycopy(header, 0, request, 0, header.length);
        int i = header.length;
        List<BlockPos> positions = new ArrayList<>(starts);
        positions.addAll(goals);
        for (BlockPos p : positions) {
            request[i++] = p.getX();
            request[i++] = p.getY();
            request[i++] = p.getZ();
        }
        for (AvoidZone zone : avoid) {
            request[i++] = zone.x();
            request[i++] = zone.y();
            request[i++] = zone.z();
            request[i++] = zone.radiusSq();
            request[i++] = zone.maxYDiff();
            request[i++] = Float.floatToRawIntBits(zone.penalty());
        }
        return request;
    }

    /** Can the player stand with their feet at (x, y, z)? Same rule as Grid::standable in Rust. */
    public static boolean standable(V5World world, int x, int y, int z) {
        int floor = world.flags(x, y - 1, z);
        return (floor & V5Flags.SOLID) != 0
                && (floor & V5Flags.FENCE_LIKE) == 0
                && V5Flags.passable(world.flags(x, y, z))
                && V5Flags.passable(world.flags(x, y + 1, z));
    }

    /** The same per-block flags Rust returns, for a route the walker already has. */
    public static int[] nodeFlags(V5World world, List<BlockPos> path) {
        int[] out = new int[path.size()];
        for (int i = 0; i < out.length; i++) {
            BlockPos p = path.get(i);
            int f = 0;
            if ((world.flags(p.getX(), p.getY(), p.getZ()) & V5Flags.FLUID) != 0) f |= 1;
            if ((world.flags(p.getX(), p.getY() + 1, p.getZ()) & V5Flags.FLUID) != 0) f |= 2;
            if (!V5Flags.passable(world.flags(p.getX(), p.getY() + 2, p.getZ()))) f |= FLAG_LOW_HEADROOM;
            if (i + 1 < out.length) {
                int dy = path.get(i + 1).getY() - p.getY();
                if (dy > 0) f |= FLAG_STEP_UP_NEXT;
                if (dy < 0) f |= 64;
            }
            out[i] = f;
        }
        return out;
    }
}
