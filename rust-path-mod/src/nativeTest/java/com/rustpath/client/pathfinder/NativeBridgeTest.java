package com.rustpath.client.pathfinder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static com.rustpath.client.pathfinder.NativePathfinder.*;

/** Runs without launching Minecraft, but loads the actual packaged native library. */
public final class NativeBridgeTest {
    private static final int SX = 32, SY = 10, SZ = 24;

    public static void main(String[] args) {
        initialize();
        require(isAvailable(), "Native library did not load: " + loadError());
        Point start = new Point(4, 65, 12), goal = new Point(26, 65, 12);
        Grid grid = flat();
        var result = find(grid, MovementCosts.vanilla(), start, goal, 50_000, 2000);
        require(result.status() == Status.FOUND, "Flat route failed");
        require(result.path().getFirst().equals(start), "Route lost its start");
        require(result.path().getLast().equals(goal), "Route did not reach the exact goal");

        for (int z = 5; z <= 18; z++) for (int y = 65; y <= 66; y++) set(grid, 15, y, z, stone());
        var detour = find(grid, MovementCosts.vanilla(), start, goal, 50_000, 2000);
        require(detour.status() == Status.FOUND, "Could not route around a wall");
        require(detour.path().stream().noneMatch(p -> p.x() == 15 && p.z() >= 5 && p.z() <= 18),
                "Walked through a wall");
        for (Point p : detour.path()) {
            require((get(grid, p.x(), p.y(), p.z()) & 1) != 0, "Route intersects a solid block");
            require((get(grid, p.x(), p.y() + 1, p.z()) & 1) != 0, "Route has no head clearance");
        }

        for (int z = 0; z < SZ; z++) for (int y = 60; y < 70; y++) set(grid, 15, y, z, 0);
        require(find(grid, MovementCosts.vanilla(), start, goal, 50_000, 2000).status() == Status.NO_PATH,
                "Crossed an unloaded chunk band");
        require(find(flat(), MovementCosts.vanilla(), start, goal, 1, 2000).status() == Status.NODE_LIMIT,
                "Did not respect the node limit");
        var same = find(flat(), MovementCosts.vanilla(), start, start, 50_000, 2000);
        require(same.status() == Status.FOUND && same.path().size() == 1, "Already-at-goal failed");

        var heap = new Grid(ByteBuffer.allocate(SX * SY * SZ * 2), 0, 60, 0, SX, SY, SZ);
        require(find(heap, MovementCosts.vanilla(), start, goal, 100, 100).status() == Status.ERROR,
                "Accepted a non-direct buffer");
        var shortBuffer = new Grid(ByteBuffer.allocateDirect(2), 0, 60, 0, SX, SY, SZ);
        require(find(shortBuffer, MovementCosts.vanilla(), start, goal, 100, 100).status() == Status.ERROR,
                "Accepted incorrect buffer dimensions");
        var unalignedBytes = ByteBuffer.allocateDirect(SX * SY * SZ * 2 + 1);
        unalignedBytes.position(1);
        var unaligned = new Grid(unalignedBytes.slice(), 0, 60, 0, SX, SY, SZ);
        require(find(unaligned, MovementCosts.vanilla(), start, goal, 100, 100).status() == Status.ERROR,
                "Accepted an unaligned u16 buffer");
        System.out.println("JNI smoke tests passed: native load, exact destination, wall detour, clearance, unknown terrain, limits, same goal, malformed buffers.");
    }

    private static Grid flat() {
        var grid = new Grid(ByteBuffer.allocateDirect(SX * SY * SZ * 2).order(ByteOrder.nativeOrder()),
                0, 60, 0, SX, SY, SZ);
        for (int x = 0; x < SX; x++) for (int y = 60; y < 70; y++) for (int z = 0; z < SZ; z++)
            set(grid, x, y, z, y <= 64 ? stone() : 1);
        return grid;
    }

    private static int stone() { return 2 | (1024 << 5); }
    private static void set(Grid grid, int x, int y, int z, int value) {
        grid.blocks().putShort(((x * SY + y - 60) * SZ + z) * 2, (short) value);
    }
    private static int get(Grid grid, int x, int y, int z) {
        return Short.toUnsignedInt(grid.blocks().getShort(((x * SY + y - 60) * SZ + z) * 2));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
