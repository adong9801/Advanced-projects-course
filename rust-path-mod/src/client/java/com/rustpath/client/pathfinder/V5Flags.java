package com.rustpath.client.pathfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Turns a Minecraft block into the flags the pathfinder uses: can the player's body be in it, can you stand on it,
 * does it block you sideways, is it a fluid, a slab or stair half, a fence, a carpet, a ladder. Based on V5's block
 * encoding (V5Loader's NativeStateEncoder / NativeVoxelFlags). The bits must match world.rs on the Rust side.
 *
 * <p>Worked out once per block state and cached by its id.
 */
public final class V5Flags {
    private V5Flags() {}

    public static final short PASSABLE = 1;
    public static final short SOLID = 1 << 1;
    public static final short PASSABLE_FLY = 1 << 2;
    public static final short BLOCKING_WALL = 1 << 3;
    public static final short FLUID = 1 << 4;
    public static final short SLAB_BOTTOM = 1 << 5;
    public static final short SLAB_TOP = 1 << 6;
    public static final short FENCE_LIKE = 1 << 7;
    public static final short STAIRS_BOTTOM = 1 << 8;
    public static final short CARPET_LIKE = 1 << 9;
    /** A ladder or vine: pressed into, it's climbed (slowly), not jumped from. */
    public static final short CLIMBABLE = 1 << 10;

    public static final short AIR = PASSABLE | PASSABLE_FLY;
    /** A block that isn't loaded counts as a wall, so routes never go into unknown terrain. */
    public static final short UNLOADED = SOLID | BLOCKING_WALL;

    private static short[] byId = new short[0];
    private static boolean[] known = new boolean[0];

    public static short of(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0) return compute(state);
        short[] cache = byId;
        boolean[] have = known;
        if (id >= cache.length) {
            synchronized (V5Flags.class) {
                int n = Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size());
                if (n > byId.length) {
                    short[] c = java.util.Arrays.copyOf(byId, n);
                    boolean[] k = java.util.Arrays.copyOf(known, n);
                    byId = c;
                    known = k;
                }
                cache = byId;
                have = known;
            }
        }
        if (have[id]) return cache[id];
        short f = compute(state);
        cache[id] = f;
        have[id] = true;
        return f;
    }

    public static boolean has(int flags, int bit) {
        return (flags & bit) != 0;
    }

    /** The body can be in it. */
    public static boolean passable(int flags) {
        return (flags & (PASSABLE | CARPET_LIKE)) != 0;
    }

    private static short compute(BlockState state) {
        if (state.isAir()) return AIR;
        int flags = 0;
        Block block = state.getBlock();
        VoxelShape shape;
        try {
            shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        } catch (Exception e) {
            return UNLOADED;
        }
        if (!state.getFluidState().isEmpty()) flags |= FLUID;
        if (block instanceof CarpetBlock) return (short) (flags | PASSABLE | PASSABLE_FLY | CARPET_LIKE);
        // Mob heads are small enough to walk through
        if (block instanceof AbstractSkullBlock) return (short) (flags | PASSABLE | PASSABLE_FLY);

        boolean passThrough = block instanceof SlabBlock || block instanceof StairBlock || block instanceof DoorBlock
                || block instanceof TrapDoorBlock || block instanceof TorchBlock || block instanceof StandingSignBlock
                || block instanceof WallSignBlock || block instanceof VegetationBlock || block instanceof BaseRailBlock
                || block instanceof VineBlock || block instanceof LadderBlock || block instanceof SnowLayerBlock
                || block instanceof PressurePlateBlock || block instanceof ButtonBlock || block instanceof RedStoneWireBlock
                || block instanceof LeverBlock || block instanceof BannerBlock || block instanceof WallBannerBlock
                || block instanceof TripWireBlock || block instanceof TripWireHookBlock;
        boolean flyPassable = block instanceof LadderBlock || block instanceof VineBlock || block instanceof BaseRailBlock
                || block instanceof StandingSignBlock || block instanceof WallSignBlock || block instanceof BannerBlock
                || block instanceof WallBannerBlock || block instanceof TripWireBlock || block instanceof TripWireHookBlock
                || block instanceof LeverBlock || block instanceof ButtonBlock || block instanceof TorchBlock
                || block instanceof RedStoneWireBlock || block instanceof PressurePlateBlock;
        if (flyPassable) flags |= PASSABLE_FLY;
        if (block instanceof LadderBlock || block instanceof VineBlock) flags |= CLIMBABLE;
        if (block instanceof FenceBlock || block instanceof FenceGateBlock || block instanceof WallBlock) {
            flags |= SOLID | BLOCKING_WALL | FENCE_LIKE;
        }
        // Thin blocks (glass panes, iron bars, chains) count as fences: you can't walk through them, stand on them or
        // step up onto them. Treated as whole blocks, routes tried to climb over pane posts the player can't land on.
        if (!shape.isEmpty() && !passThrough && !(block instanceof SlabBlock) && !(block instanceof StairBlock) && thinTop(shape))
            flags |= SOLID | BLOCKING_WALL | FENCE_LIKE;

        if (block instanceof SlabBlock) {
            flags |= SOLID;
            SlabType type = state.getValue(SlabBlock.TYPE);
            if (type == SlabType.BOTTOM) flags |= SLAB_BOTTOM;
            else if (type == SlabType.TOP) flags |= SLAB_TOP | BLOCKING_WALL;
            else flags |= BLOCKING_WALL;
        } else if (block instanceof StairBlock) {
            flags |= SOLID;
            if (state.getValue(StairBlock.HALF) == Half.BOTTOM) flags |= STAIRS_BOTTOM;
        } else if (shape.isEmpty()) {
            flags |= PASSABLE;
        } else {
            flags |= SOLID;
            AABB box = shape.bounds();
            if (box.maxY - box.minY >= 0.5 && !passThrough) flags |= BLOCKING_WALL;
        }
        if (passable(flags)) flags |= PASSABLE_FLY;
        return (short) flags;
    }

    /**
     * Is the top of this shape less than half a block in area (a pane, bars, a post)? Then there's nothing to stand on.
     * This goes by the area of the top, not the overall width: an L-shaped pane is wide but still too thin to stand on.
     */
    public static boolean thinTop(VoxelShape shape) {
        double top = shape.max(net.minecraft.core.Direction.Axis.Y), area = 0;
        for (AABB b : shape.toAabbs()) if (b.maxY >= top - 0.01) area += (b.maxX - b.minX) * (b.maxZ - b.minZ);
        return area < 0.5;
    }
}
