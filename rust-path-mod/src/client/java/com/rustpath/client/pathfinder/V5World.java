package com.rustpath.client.pathfinder;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The blocks as the pathfinder sees them ({@link V5Flags}).
 *
 * <p>Two ways to use it. The walker reads blocks straight from the loaded chunks, a 16x16x16 section at a time.
 * Before a Rust search, {@link #beginCapture} sets up a box around the start and goal, and {@link #captureBatch}
 * copies every block in it into a direct ByteBuffer that Rust reads without copying. That has to happen on the
 * client thread, so it's done a few milliseconds per tick to avoid freezing the game.
 */
public final class V5World {
    /** {@link #shared()} is rebuilt after this long, since blocks change (mined, placed). */
    private static final long MAX_AGE_MS = 1000;
    private static final short[] AIR_SECTION = new short[0];
    private static final short[] UNLOADED_SECTION = new short[0];

    private static V5World shared;

    final Level level;
    final int minY, maxY;
    private final long createdAt = System.currentTimeMillis();
    private final Long2ObjectOpenHashMap<short[]> sections = new Long2ObjectOpenHashMap<>();

    // The snapshot for Rust: one short of flags per block, and a byte of diagonal-squeeze bits per block
    ByteBuffer nativeCells, nativeCorners;
    int cx0, cy0, cz0, sx, sy, sz; // the snapshot box's lowest corner and size
    private int capturePhase; // 0: copying flags, 1: working out the corners, 2: done
    private int copied, cellCount;

    public V5World(Level level) {
        this.level = level;
        this.minY = level.getMinY();
        this.maxY = level.getMinY() + level.getHeight();
    }

    /** One world for every search on the main thread within a second. */
    public static V5World shared() {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        V5World w = shared;
        if (w == null || w.level != level || System.currentTimeMillis() - w.createdAt > MAX_AGE_MS) {
            w = new V5World(level);
            shared = w;
        }
        return w;
    }

    public short flags(int x, int y, int z) {
        if (y < minY || y >= maxY) return V5Flags.AIR;
        if (capturePhase > 0) {
            int i = captureIndex(x, y, z);
            return i < 0 ? V5Flags.UNLOADED : nativeCells.getShort(i * 2);
        }
        long key = sectionKey(x >> 4, y >> 4, z >> 4);
        short[] section = sections.get(key);
        if (section == null) {
            section = load(x >> 4, y >> 4, z >> 4);
            sections.put(key, section);
        }
        if (section == AIR_SECTION) return V5Flags.AIR;
        if (section == UNLOADED_SECTION) return V5Flags.UNLOADED;
        return section[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    private short[] load(int chunkX, int sectionY, int chunkZ) {
        try {
            ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) return UNLOADED_SECTION;
            LevelChunkSection[] sections = chunk.getSections();
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= sections.length) return AIR_SECTION;
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir()) return AIR_SECTION;
            short[] out = new short[4096];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        out[(y << 8) | (z << 4) | x] = V5Flags.of(section.getBlockState(x, y, z));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return UNLOADED_SECTION;
        }
    }

    /**
     * Can a player (0.6 wide, 1.8 tall) get diagonally from (x, y, z) to (x+dx, y, z+dz) even though the two
     * blocks beside the corner aren't open? The search treats a glass pane as a whole block, but two panes leave a
     * gap at the corner between them you can walk through. Checked with the blocks' real collision shapes, by
     * sliding the player's box along the diagonal.
     */
    boolean squeezes(int x, int y, int z, int dx, int dz) {
        if (capturePhase == 2) {
            int i = captureIndex(x, y, z);
            int bit = dx > 0 ? (dz < 0 ? 0 : 2) : (dz < 0 ? 1 : 3);
            return i >= 0 && (nativeCorners.get(i) & (1 << bit)) != 0;
        }
        BlockPos[] sides = {
                new BlockPos(x + dx, y, z), new BlockPos(x + dx, y + 1, z),
                new BlockPos(x, y, z + dz), new BlockPos(x, y + 1, z + dz)
        };
        List<AABB> shapes = new ArrayList<>();
        for (BlockPos side : sides) {
            // Never squeeze through water or lava
            if (V5Flags.has(flags(side.getX(), side.getY(), side.getZ()), V5Flags.FLUID)) return false;
            for (AABB box : level.getBlockState(side).getCollisionShape(level, side).toAabbs()) {
                shapes.add(box.move(side));
            }
        }
        for (double t = 0.15; t < 0.9; t += 0.1) {
            double px = x + 0.5 + dx * t, pz = z + 0.5 + dz * t;
            AABB player = new AABB(px - 0.3, y + 0.01, pz - 0.3, px + 0.3, y + 1.8, pz + 0.3);
            for (AABB shape : shapes) if (shape.intersects(player)) return false;
        }
        return true;
    }

    private static long sectionKey(int chunkX, int sectionY, int chunkZ) {
        return ((long) (chunkX & 0x3FFFFF) << 42) | ((long) (sectionY & 0xFFFFF) << 22) | (chunkZ & 0x3FFFFF);
    }

    /**
     * Set up the snapshot box: the starts and goals plus 32 blocks round them sideways and 24 up and down.
     * The blocks are copied in by {@link #captureBatch}.
     */
    public void beginCapture(List<BlockPos> starts, Collection<BlockPos> goals) {
        if (starts.isEmpty() || goals.isEmpty()) throw new IllegalArgumentException("Choose a start and destination.");
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0;
        int x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        List<BlockPos> points = new ArrayList<>(starts);
        points.addAll(goals);
        for (BlockPos p : points) {
            x0 = Math.min(x0, p.getX());
            x1 = Math.max(x1, p.getX());
            y0 = Math.min(y0, p.getY());
            y1 = Math.max(y1, p.getY());
            z0 = Math.min(z0, p.getZ());
            z1 = Math.max(z1, p.getZ());
        }
        if ((long) x1 - x0 > 128 || (long) z1 - z0 > 128 || (long) y1 - y0 > 48) {
            throw new IllegalArgumentException("Choose a target within 128 blocks horizontally and 48 vertically.");
        }
        cx0 = x0 - 32;
        cz0 = z0 - 32;
        cy0 = Math.max(minY, y0 - 24);
        sx = x1 - cx0 + 33;
        sz = z1 - cz0 + 33;
        sy = Math.min(maxY - 1, y1 + 24) - cy0 + 1;
        long cells = (long) sx * sy * sz;
        if (sy <= 0 || cells > 16_000_000) {
            throw new IllegalArgumentException("Search area is too large or outside world height.");
        }
        cellCount = (int) cells;
        nativeCells = ByteBuffer.allocateDirect(cellCount * 2).order(ByteOrder.nativeOrder());
        nativeCorners = ByteBuffer.allocateDirect(cellCount);
        copied = 0;
        capturePhase = 0;
    }

    /** Where (x, y, z) is in the snapshot buffers, or -1 if it's outside the box. */
    int captureIndex(int x, int y, int z) {
        int lx = x - cx0, ly = y - cy0, lz = z - cz0;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return -1;
        return (lx * sy + ly) * sz + lz;
    }

    /**
     * Copy blocks into the snapshot for up to 4 ms. Two passes: first every block's flags, then, for blocks you
     * can stand in, which diagonals you can squeeze through. Returns true once it's all done.
     */
    public boolean captureBatch() {
        if (capturePhase == 2) return true;
        long deadline = System.nanoTime() + 4_000_000;
        do {
            int end = Math.min(cellCount, copied + 256);
            while (copied < end) {
                int lx = copied / (sy * sz), ly = copied / sz % sy, lz = copied % sz;
                int x = cx0 + lx, y = cy0 + ly, z = cz0 + lz;
                if (capturePhase == 0) {
                    nativeCells.putShort(copied * 2, flags(x, y, z));
                } else if (V5Search.standable(this, x, y, z)) {
                    nativeCorners.put(copied, (byte) cornerBits(x, y, z));
                }
                copied++;
            }
            if (copied == cellCount) {
                capturePhase++;
                copied = 0;
            }
        } while (capturePhase < 2 && System.nanoTime() < deadline);
        return capturePhase == 2;
    }

    /** One bit per diagonal you can only get through by squeezing past the corner. */
    private int cornerBits(int x, int y, int z) {
        int[] dx = {1, -1, 1, -1};
        int[] dz = {-1, -1, 1, 1};
        int bits = 0;
        for (int d = 0; d < 4; d++) {
            int tx = x + dx[d], tz = z + dz[d];
            if (!V5Search.standable(this, tx, y, tz)) continue;
            boolean open = V5Flags.passable(flags(tx, y, z)) && V5Flags.passable(flags(x, y, tz))
                    && V5Flags.passable(flags(tx, y + 1, z)) && V5Flags.passable(flags(x, y + 1, tz));
            if (!open && squeezes(x, y, z, dx[d], dz[d])) bits |= 1 << d;
        }
        return bits;
    }

    public boolean captured() {
        return capturePhase == 2;
    }
}
