package com.rustpath.client.pathfinder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Normal walking/jumping costs, in ticks. Layout must match Rust's costs.rs. */
public final class MovementCosts {
    private MovementCosts() {}

    public static ByteBuffer vanilla() {
        var buffer = ByteBuffer.allocateDirect(6 * 8 + 257 * 8 + 4).order(ByteOrder.nativeOrder());
        double walk = 1.0 / (0.1 * (0.16277136 / Math.pow(0.91, 3)) * 10.0);
        double sprint = walk / 1.3;
        buffer.putDouble(sprint).putDouble(walk).putDouble(walk / 0.3);
        buffer.putDouble(9.0).putDouble(walk * 0.8).putDouble(walk * 0.2);
        for (int distance = 0; distance <= 256; distance++) buffer.putDouble(fallTicks(distance));
        buffer.putInt(1); // No assumed Jump Boost effect.
        return buffer.flip();
    }

    private static double fallTicks(int distance) {
        double fallen = 0, velocity = 0;
        int ticks = 0;
        while (fallen < distance) {
            velocity = (velocity + 0.08) * 0.98;
            if (fallen + velocity >= distance) return ticks + (distance - fallen) / velocity;
            fallen += velocity;
            ticks++;
        }
        return ticks;
    }
}
