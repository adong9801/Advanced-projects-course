package com.rustpath.client.pathfinder;

import com.rustpath.reference.BlockPos;
import com.rustpath.reference.V5Search.AvoidZone;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Comparison tests: the same worlds, starts, goals, avoid zones and seeds go into the Java search (a frozen copy of
 * the Java mod's, in com.rustpath.reference) and the Rust one, and everything has to come out exactly the same: the route,
 * the flags on each block, how many blocks were explored, which start was used and the route's signature.
 */
public final class V5ParityTest {
    // Block flags (V5Flags)
    private static final int AIR = 5;
    private static final int STONE = 10;
    private static final int BOTTOM_SLAB = 34;
    private static final int STAIRS = 258;
    private static final int WATER = 21;
    private static final int FENCE = 138;

    private static int cases;

    /** A made-up world: a box of block flags, plus the corner-squeeze bits. Outside the box is unloaded. */
    static final class Grid {
        final int sx, sy, sz, mx, my, mz;
        final ByteBuffer blocks, corners;

        Grid(int sx, int sy, int sz) {
            this(sx, sy, sz, -24, -8, -24);
        }

        Grid(int sx, int sy, int sz, int mx, int my, int mz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.mx = mx;
            this.my = my;
            this.mz = mz;
            blocks = ByteBuffer.allocateDirect(sx * sy * sz * 2).order(ByteOrder.nativeOrder());
            corners = ByteBuffer.allocateDirect(sx * sy * sz);
            fill(mx, my, mz, mx + sx - 1, my + sy - 1, mz + sz - 1, AIR);
        }

        int index(int x, int y, int z) {
            x -= mx;
            y -= my;
            z -= mz;
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return -1;
            return (x * sy + y) * sz + z;
        }

        short get(int x, int y, int z) {
            if (y < -64 || y >= 320) return AIR;
            int i = index(x, y, z);
            return i < 0 ? STONE : blocks.getShort(i * 2);
        }

        void set(int x, int y, int z, int flags) {
            blocks.putShort(index(x, y, z) * 2, (short) flags);
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, int flags) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        set(x, y, z, flags);
        }

        boolean squeeze(int x, int y, int z, int dx, int dz) {
            int i = index(x, y, z);
            int bit = dx > 0 ? (dz < 0 ? 0 : 2) : (dz < 0 ? 1 : 3);
            return i >= 0 && (corners.get(i) & (1 << bit)) != 0;
        }
    }

    /** A stone floor with its top at y = 0, so you stand at y = 1. */
    static Grid flat() {
        Grid g = new Grid(48, 40, 48);
        g.fill(-24, -8, -24, 23, 0, 23, STONE);
        return g;
    }

    static BlockPos p(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    static void test(String label, Grid g, List<BlockPos> starts, List<BlockPos> goals, int seed, int climb,
                     List<AvoidZone> zones, int limit) {
        com.rustpath.reference.V5Search.maxClimb = climb;
        var java = com.rustpath.reference.V5Search.find(new com.rustpath.reference.V5World(g::get, g::squeeze),
                starts, goals, limit, seed, zones, 0);

        // The same request V5Search.buildRequest makes
        int[] header = {1, g.mx, g.my, g.mz, g.sx, g.sy, g.sz, -64, 320, limit, seed, climb, 0,
                starts.size(), goals.size(), zones.size()};
        int[] request = new int[header.length + (starts.size() + goals.size()) * 3 + zones.size() * 6];
        System.arraycopy(header, 0, request, 0, header.length);
        int i = header.length;
        var points = new ArrayList<>(starts);
        points.addAll(goals);
        for (var p : points) {
            request[i++] = p.x();
            request[i++] = p.y();
            request[i++] = p.z();
        }
        for (var z : zones) {
            request[i++] = z.x();
            request[i++] = z.y();
            request[i++] = z.z();
            request[i++] = z.radiusSq();
            request[i++] = z.maxYDiff();
            request[i++] = Float.floatToRawIntBits(z.penalty());
        }
        int[] rust = NativePathfinder.nativeFindV5(g.blocks, g.corners, request);
        require(rust != null && rust.length >= 6, label + " bad response");

        if (java == null) {
            // No route in Java: Rust has to fail too (no path, or out of iterations)
            require(rust[0] == 0 || rust[0] == 2, label + " Java found no route, Rust status " + rust[0]);
        } else {
            require(rust[0] == 1, label + " Rust found no route, status " + rust[0]);
            var path = new ArrayList<BlockPos>();
            var flags = new ArrayList<Integer>();
            for (int k = 0; k < rust[2]; k++) {
                int j = 6 + k * 4;
                path.add(p(rust[j], rust[j + 1], rust[j + 2]));
                flags.add(rust[j + 3]);
            }
            require(path.equals(java.path()), label + " route mismatch\nJava " + java.path() + "\nRust " + path);
            require(rust[1] == java.explored(), label + " explored " + rust[1] + " != " + java.explored());
            require(rust[3] == java.startIndex(), label + " start mismatch");
            long signature = (rust[4] & 0xffffffffL) | ((long) rust[5] << 32);
            require(signature == java.signature(), label + " signature mismatch");
            for (int k = 0; k < flags.size(); k++) {
                require(flags.get(k) == java.flags()[k], label + " annotation mismatch");
            }
        }
        cases++;
    }

    /** One start, one goal, tried with four different move orders. */
    static void test(String label, Grid g, BlockPos from, BlockPos to) {
        for (int seed : new int[]{0, 1, 7, 15}) {
            test(label + " seed " + seed, g, List.of(from), List.of(to), seed, 1, List.of(), 100_000);
        }
    }

    public static void main(String[] args) {
        NativePathfinder.initialize();
        require(NativePathfinder.isAvailable(), NativePathfinder.loadError());

        Grid g = flat();
        test("flat", g, p(-15, 1, 0), p(15, 1, 0));
        test("same", g, p(0, 1, 0), p(0, 1, 0));
        g.fill(-2, 1, -12, 2, 4, 12, STONE);
        test("wall", g, p(-15, 1, 0), p(15, 1, 0));

        g = flat();
        g.fill(0, 1, -24, 23, 1, 23, STONE);
        test("step", g, p(-15, 1, 0), p(15, 2, 0));
        g.fill(-1, 1, -24, -1, 1, 23, BOTTOM_SLAB);
        test("slab", g, p(-15, 1, 0), p(15, 2, 0));
        g.fill(-1, 1, -24, -1, 1, 23, STAIRS);
        test("stairs", g, p(-15, 1, 0), p(15, 2, 0));

        for (int drop : new int[]{1, 2, 3, 6, 20, 21}) {
            g = new Grid(48, 56, 48, -24, -30, -24);
            g.fill(-24, 0, -24, -1, 0, 23, STONE);
            g.fill(0, -drop, -24, 23, -drop, 23, STONE);
            test("drop " + drop, g, p(-10, 1, 0), p(10, 1 - drop, 0));
        }

        g = flat();
        g.fill(-24, 3, -24, 23, 3, 23, STONE);
        g.fill(0, 1, -24, 0, 1, 23, STONE);
        test("low ceiling", g, p(-10, 1, 0), p(10, 1, 0));

        g = flat();
        g.fill(-1, 1, -24, 1, 2, 23, WATER);
        test("water", g, p(-10, 1, 0), p(10, 1, 0));

        g = flat();
        g.fill(-1, 1, -24, 1, 2, 23, FENCE);
        test("fence", g, p(-10, 1, 0), p(10, 1, 0));

        g = flat();
        test("multi", g, List.of(p(-20, 1, 0), p(14, 1, 0)), List.of(p(15, 1, 0), p(0, 1, 10)), 0, 1,
                List.of(new AvoidZone(-10, 1, 0, 36, 2, 100)), 100_000);
        test("limit", g, List.of(p(-20, 1, 0)), List.of(p(20, 1, 0)), 0, 1, List.of(), 1);

        g = flat();
        g.fill(0, 1, -24, 23, 3, 23, STONE);
        test("boost", g, List.of(p(-10, 1, 0)), List.of(p(10, 4, 0)), 0, 3, List.of(), 100_000);

        // Only the corner bits come from Minecraft's block shapes. Check Rust uses them the same way.
        g = flat();
        g.set(1, 1, 0, FENCE);
        g.set(0, 1, 1, FENCE);
        g.corners.put(g.index(0, 1, 0), (byte) 4);
        test("pane corner", g, p(0, 1, 0), p(5, 1, 5));

        for (int seed = 0; seed < 60; seed++) {
            g = flat();
            Random random = new Random(seed);
            for (int j = 0; j < 120; j++) {
                int x = random.nextInt(35) - 17, z = random.nextInt(35) - 17, height = 1 + random.nextInt(4);
                g.fill(x, 1, z, x, height, z, random.nextInt(5) == 0 ? FENCE : STONE);
            }
            g.fill(-20, 1, -1, -18, 5, 1, AIR);
            g.fill(18, 1, -1, 20, 5, 1, AIR);
            test("random " + seed, g, p(-19, 1, 0), p(19, 1, 0));
        }

        invalidInputs();
        System.out.println("V5 parity: " + cases
                + " exact Java/Rust cases passed (paths, annotations, explored count, start and signature).");
    }

    /** Bad requests must come back as -1, never crash or read past a buffer. */
    private static void invalidInputs() {
        require(NativePathfinder.nativeFindV5(null, null, null)[0] == -1, "Null ABI accepted");
        Grid g = flat();
        int[] q = {1, g.mx, g.my, g.mz, g.sx, g.sy, g.sz, -64, 320, 10000, 0, 1, 1000, 1, 1, 0, 0, 1, 0, 10, 1, 0};
        require(NativePathfinder.nativeFindV5(ByteBuffer.allocate(g.blocks.capacity()), g.corners, q)[0] == -1,
                "V5 accepted heap memory");
        require(NativePathfinder.nativeFindV5(ByteBuffer.allocateDirect(2), g.corners, q)[0] == -1,
                "V5 accepted short buffer");
        var bytes = ByteBuffer.allocateDirect(g.blocks.capacity() + 1);
        bytes.position(1);
        require(NativePathfinder.nativeFindV5(bytes.slice(), g.corners, q)[0] == -1, "V5 accepted unaligned buffer");
        require(NativePathfinder.nativeFindV5(g.blocks, ByteBuffer.allocateDirect(1), q)[0] == -1,
                "V5 accepted short corner buffer");
        int[] changed = q.clone();
        changed[0] = 2;
        require(NativePathfinder.nativeFindV5(g.blocks, g.corners, changed)[0] == -1, "V5 accepted wrong ABI");
        changed = q.clone();
        changed[13] = -1;
        require(NativePathfinder.nativeFindV5(g.blocks, g.corners, changed)[0] == -1, "V5 accepted negative count");
        changed = q.clone();
        changed[4] = Integer.MAX_VALUE;
        require(NativePathfinder.nativeFindV5(g.blocks, g.corners, changed)[0] == -1,
                "V5 accepted enormous dimensions");
        System.out.println("V5 JNI validation passed: nulls, heap memory, alignment, dimensions, corner buffer, ABI version and counts.");
    }

    static void require(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
