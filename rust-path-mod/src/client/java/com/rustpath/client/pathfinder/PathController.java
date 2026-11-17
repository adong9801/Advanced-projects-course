package com.rustpath.client.pathfinder;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.rustpath.client.pathfinder.NativePathfinder.Point;

/**
 * Runs the /pathfind commands on the main thread.
 *
 * <p>A preview goes: read the blocks into a snapshot (a few ms each tick), search it with Rust on a background
 * thread, then show the route. A walk hands the destination to {@link V5PathWalker}, which searches (with Rust
 * too) and moves the player.
 */
public final class PathController {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Rust route preview");
        thread.setDaemon(true);
        return thread;
    });

    private V5World snapshot;
    private List<BlockPos> starts = List.of();
    private List<BlockPos> goals = List.of();
    private Future<V5Search.Result> search;
    private List<BlockPos> displayedNodes = List.of();
    private List<Vec3> path = List.of();
    private ClientLevel activeLevel;
    private Point destination;
    private boolean walking;
    private long requestedAt;

    /** /pathfind x y z: find a route and show it, without moving. */
    public int request(Minecraft client, Point to) {
        if (!ready(client)) return 0;
        clear();
        activeLevel = client.level;
        destination = to;
        requestedAt = System.nanoTime();
        try {
            snapshot = new V5World(client.level);
            starts = V5PathWalker.bodyStarts(snapshot, client.player);
            goals = V5PathWalker.resolveGoals(snapshot, List.of(new BlockPos(to.x(), to.y(), to.z())));
            snapshot.beginCapture(starts, goals);
            say(client, "Reading blocks, then finding a Rust V5 route...");
            return 1;
        } catch (IllegalArgumentException e) {
            clear();
            say(client, e.getMessage());
            return 0;
        }
    }

    /** /pathfind walk [x y z]: walk there (or to the last previewed destination). */
    public int walk(Minecraft client, Point to) {
        if (!ready(client)) return 0;
        if (to == null) {
            to = destination;
            if (to == null) {
                say(client, "Preview a route first, or use /pathfind walk x y z.");
                return 0;
            }
        }
        var player = client.player;
        if (Math.abs((long) to.x() - player.getBlockX()) > 128
                || Math.abs((long) to.z() - player.getBlockZ()) > 128
                || Math.abs((long) to.y() - player.getBlockY()) > 48) {
            say(client, "Choose a target within 128 blocks horizontally and 48 vertically.");
            return 0;
        }
        clear();
        activeLevel = client.level;
        destination = to;
        walking = true;
        V5Search.maxClimb = 1;
        V5PathWalker.goTo(List.of(new BlockPos(to.x(), to.y(), to.z())), 1.0);
        say(client, "Walking with the Rust search and the Java walker. /pathfind stop or F7 stops.");
        return 1;
    }

    private boolean ready(Minecraft client) {
        if (client.player == null || client.level == null) return false;
        if (!NativePathfinder.isAvailable()) {
            say(client, "Rust unavailable: " + NativePathfinder.loadError());
            return false;
        }
        return true;
    }

    public void tick(Minecraft client) {
        // Left the world (or changed dimension): forget everything
        if (activeLevel != null && (client.level != activeLevel || client.player == null)) {
            clear();
            return;
        }
        if (client.player == null) return;
        if (walking && InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_F7)) {
            clear();
            say(client, "Stopped.");
            return;
        }
        try {
            if (walking) {
                tickWalk(client);
            } else {
                tickPreview(client);
            }
        } catch (Exception | LinkageError e) {
            LoggerFactory.getLogger("rust-path-mod").error("Pathfinder failed", e);
            clear();
            say(client, "Pathfinder stopped; see the game log for details.");
        }
    }

    private void tickWalk(Minecraft client) {
        V5PathWalker.tick();
        var nodes = V5PathWalker.currentPath();
        if (nodes != null && nodes != displayedNodes) show(nodes);
        if (!V5PathWalker.isActive()) {
            walking = false;
            say(client, V5PathWalker.succeeded()
                    ? "Arrived."
                    : "Walk ended: " + Objects.toString(V5PathWalker.lastFailure(), "stopped"));
        }
    }

    private void tickPreview(Minecraft client) throws Exception {
        // Once every block is copied, search on the worker thread
        if (snapshot != null && snapshot.captureBatch()) {
            V5World frozen = snapshot;
            List<BlockPos> from = starts, to = goals;
            snapshot = null;
            search = worker.submit(() -> V5Search.find(frozen, from, to, V5Search.MAX_ITERATIONS, 0, List.of(),
                    System.currentTimeMillis() + 5000));
        }
        if (search != null && search.isDone()) {
            var result = search.get();
            search = null;
            if (result == null) {
                say(client, "No route within the loaded search area and search budget.");
                return;
            }
            show(result.path());
            double totalMs = (System.nanoTime() - requestedAt) / 1_000_000.0;
            say(client, String.format(Locale.ROOT,
                    "Route found: %d steps, %d explored, %d ms search (%.0f ms total). /pathfind walk to follow it.",
                    Math.max(0, result.path().size() - 1), result.explored(), result.ms(), totalMs));
        }
    }

    private void show(List<BlockPos> nodes) {
        displayedNodes = nodes;
        // Draw the exact line the walker looks along, moved down from eye height to the ground
        path = V5PathWalker.lineLookPoints(nodes).stream().map(p -> p.add(0, -1.54, 0)).toList();
    }

    public void frame() {
        if (walking) V5PathWalker.frame();
    }

    public void clear() {
        snapshot = null;
        if (search != null) search.cancel(true);
        search = null;
        V5PathWalker.stop();
        walking = false;
        path = List.of();
        displayedNodes = List.of();
        activeLevel = null;
    }

    public List<Vec3> path() {
        return path;
    }

    public boolean isWalking() {
        return walking;
    }

    public ClientLevel level() {
        return activeLevel;
    }

    public boolean busy() {
        return snapshot != null || search != null || V5PathWalker.isSearching();
    }

    public String status() {
        if (walking) return busy() ? "Searching for a walk..." : "Walking.";
        return busy() ? "Searching..." : path.size() + " route points displayed.";
    }

    public void close() {
        clear();
        worker.shutdownNow();
        V5PathWalker.shutdown();
    }

    public static void say(Minecraft client, String message) {
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[Pathfinder] " + message));
    }
}
