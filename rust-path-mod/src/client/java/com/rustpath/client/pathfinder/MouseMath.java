package com.rustpath.client.pathfinder;

import net.minecraft.client.Minecraft;

/** Small angle helpers for turning the view. */
final class MouseMath {
    private MouseMath() {}

    /**
     * The smallest amount the view can turn with a real mouse at the current sensitivity (Minecraft's own
     * formula). Turning in whole steps of this looks like a real mouse.
     */
    static float mouseStep() {
        double sensitivity = 0.5;
        try {
            sensitivity = Minecraft.getInstance().options.sensitivity().get();
        } catch (Exception ignored) {
        }
        double f = sensitivity * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    /** An angle in degrees, wrapped to -180..180. */
    static double wrap(double angle) {
        angle %= 360;
        if (angle > 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }
}
