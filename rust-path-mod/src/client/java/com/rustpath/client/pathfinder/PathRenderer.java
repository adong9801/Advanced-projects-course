package com.rustpath.client.pathfinder;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Draws the route with Minecraft's own debug line renderer: cyan line, green start, yellow end. */
public final class PathRenderer {
    private static final int LINE = 0xFF33DDFF;
    private static final int START = 0xFF44FF88;
    private static final int END = 0xFFFFDD44;

    public static void extract(LevelExtractionContext context, PathController controller) {
        var path = controller.path();
        if (path.isEmpty() || controller.level() != context.level()) return;
        try (var ignored = context.levelRenderer().collectPerFrameGizmos()) {
            for (int i = 1; i < path.size(); i++) {
                Gizmos.line(path.get(i - 1), path.get(i), LINE, 3.0f).setAlwaysOnTop();
            }
            marker(path.getFirst(), START);
            marker(path.getLast(), END);
        }
    }

    private static void marker(Vec3 p, int color) {
        Gizmos.cuboid(new AABB(p.x - 0.2, p.y, p.z - 0.2, p.x + 0.2, p.y + 0.4, p.z + 0.2),
                GizmoStyle.stroke(color, 3.0f)).setAlwaysOnTop();
    }
}
