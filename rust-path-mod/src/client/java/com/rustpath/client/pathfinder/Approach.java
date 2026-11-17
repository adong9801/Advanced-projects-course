package com.rustpath.client.pathfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Walks the last bit onto an exact spot with the movement keys. The keys are let go a little early, counting how far
 * the player still slides once they're up (about 1.2 ticks of speed on the ground). A small overshoot isn't walked
 * back, because reversing for a few hundredths of a block made it shuffle back and forth over the spot.
 */
public final class Approach {
    private Approach() {}

    /** An overshoot under this (blocks) is left, not walked back. */
    private static final double NO_REVERSE = 0.3;

    /**
     * Keys toward the spot {@code dx}, {@code dz} off (world), held till what you'd slide gets you within
     * {@code tolerance}. True while still going; false once you're there or will slide there (keys let go).
     */
    public static boolean keys(Minecraft mc, double dx, double dz, double tolerance) {
        var p = mc.player;
        if (p == null) return false;
        Vec3 v = p.getDeltaMovement();
        double slide = p.onGround() ? 1.2 : 0.5;
        double rx = dx - v.x * slide, rz = dz - v.z * slide, left = Math.hypot(rx, rz);
        double speed = Math.hypot(v.x, v.z);
        boolean back = speed > 0.01 && rx * v.x + rz * v.z < 0; // the rest is behind the way you're going
        if (left <= tolerance || back && left < NO_REVERSE) {
            release(mc);
            return false;
        }
        double yaw = Math.toRadians(p.getYRot());
        double fwd = rx * -Math.sin(yaw) + rz * Math.cos(yaw), right = rx * -Math.cos(yaw) + rz * -Math.sin(yaw);
        double len = Math.hypot(fwd, right);
        mc.options.keyUp.setDown(fwd > 0.38 * len);
        mc.options.keyDown.setDown(fwd < -0.38 * len);
        mc.options.keyRight.setDown(right > 0.38 * len);
        mc.options.keyLeft.setDown(right < -0.38 * len);
        return true;
    }

    /** At the spot ({@code dx}, {@code dz} off) within {@code tolerance}, near enough stopped. */
    public static boolean there(Minecraft mc, double dx, double dz, double tolerance) {
        var p = mc.player;
        if (p == null) return false;
        Vec3 v = p.getDeltaMovement();
        return Math.hypot(dx, dz) <= tolerance && Math.hypot(v.x, v.z) < 0.03;
    }

    public static void release(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
    }
}
