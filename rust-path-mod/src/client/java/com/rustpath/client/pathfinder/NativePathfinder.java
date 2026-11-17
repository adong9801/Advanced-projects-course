package com.rustpath.client.pathfinder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads the Rust library and declares the functions it provides. It doesn't use any Minecraft classes, so the
 * tests can run it without starting the game.
 */
public final class NativePathfinder {
    private static boolean initialized;
    private static boolean available;
    private static String loadError;

    public record Point(int x, int y, int z) {}

    /** Cells are native-endian unsigned shorts, in x/y/z order. */
    public record Grid(ByteBuffer blocks, int minX, int minY, int minZ,
                       int sizeX, int sizeY, int sizeZ) {}

    public enum Status { FOUND, NO_PATH, NODE_LIMIT, TIMED_OUT, ERROR }
    public record Result(Status status, int nodesExplored, List<Point> path) {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String platform = os.contains("mac") ? "macos" : os.contains("win") ? "windows" : "linux";
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
            arch = switch (arch) {
                case "arm64", "aarch64" -> "aarch64";
                case "amd64", "x86_64" -> "x86_64";
                default -> arch;
            };
            // The library is packed inside the mod jar. Copy it to a temp file so Java can load it.
            String name = System.mapLibraryName("rust_pathfinder");
            String resource = "/natives/" + platform + "-" + arch + "/" + name;
            try (var input = NativePathfinder.class.getResourceAsStream(resource)) {
                if (input == null) throw new IOException("No native engine for " + platform + "-" + arch
                        + ". Build this mod on that computer with Cargo installed.");
                Path directory = Files.createTempDirectory("rust-path-mod-");
                Path library = directory.resolve(name);
                directory.toFile().deleteOnExit();
                Files.copy(input, library);
                library.toFile().deleteOnExit();
                System.load(library.toAbsolutePath().toString());
            }
            // A working library answers a request of all nulls with -1 (bad input)
            int[] probe = nativeFindV5(null, null, null);
            if (probe == null || probe.length != 6 || probe[0] != -1)
                throw new IOException("Incompatible native engine response.");
            available = true;
        } catch (IOException | SecurityException | UnsatisfiedLinkError e) {
            loadError = e.getMessage();
        }
    }

    public static boolean isAvailable() { return available; }
    public static String loadError() { return loadError; }

    /** The first version's search. Only NativeBridgeTest uses it now; the mod uses {@link V5Search}. */
    public static Result find(Grid grid, ByteBuffer costs, Point start, Point goal,
                              int maxNodes, long maxMillis) {
        if (!available) return new Result(Status.ERROR, 0, List.of());
        double searchDistance = 2.0 * Math.max(grid.sizeX(), Math.max(grid.sizeY(), grid.sizeZ()));
        int[] raw = nativeFindPathV2(grid.blocks(), grid.minX(), grid.minY(), grid.minZ(),
                grid.sizeX(), grid.sizeY(), grid.sizeZ(), costs,
                start.x(), start.y(), start.z(), goal.x(), goal.y(), goal.z(),
                maxNodes, maxMillis, searchDistance);
        if (raw == null || raw.length < 3 || raw[2] < 0 || 3L + raw[2] * 3L != raw.length)
            return new Result(Status.ERROR, 0, List.of());
        Status status = switch (raw[0]) {
            case 1 -> Status.FOUND;
            case 0 -> Status.NO_PATH;
            case 2 -> Status.NODE_LIMIT;
            case 3 -> Status.TIMED_OUT;
            default -> Status.ERROR;
        };
        List<Point> path = new ArrayList<>(raw[2]);
        for (int i = 3; i < raw.length; i += 3) path.add(new Point(raw[i], raw[i + 1], raw[i + 2]));
        return new Result(status, raw[1], List.copyOf(path));
    }

    public static native int[] nativeFindV5(ByteBuffer cells, ByteBuffer corners, int[] request);

    private static native int[] nativeFindPathV2(ByteBuffer blocks,
            int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, ByteBuffer costs,
            int startX, int startY, int startZ, int goalX, int goalY, int goalZ,
            int maxNodes, long maxMillis, double maxSearchDistance);
}
