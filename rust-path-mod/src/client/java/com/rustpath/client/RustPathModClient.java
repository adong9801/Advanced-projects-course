package com.rustpath.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.rustpath.client.pathfinder.NativePathfinder;
import com.rustpath.client.pathfinder.NativePathfinder.Point;
import com.rustpath.client.pathfinder.PathController;
import com.rustpath.client.pathfinder.PathRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.LoggerFactory;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class RustPathModClient implements ClientModInitializer {
    private static final PathController paths = new PathController();

    public static PathController controller() {
        return paths;
    }

    @Override
    public void onInitializeClient() {
        NativePathfinder.initialize();
        var logger = LoggerFactory.getLogger("rust-path-mod");
        if (NativePathfinder.isAvailable()) logger.info("Rust pathfinder ready. Use /pathfind x y z.");
        else logger.error("Rust pathfinder unavailable: {}", NativePathfinder.loadError());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
        ClientTickEvents.END_CLIENT_TICK.register(paths::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> paths.clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> paths.close());
        LevelRenderEvents.END_EXTRACTION.register(context -> {
            paths.frame();
            PathRenderer.extract(context, paths);
        });
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("pathfind")
                .executes(context -> say(context, "/pathfind <x> <y> <z> | /pathfind look | /pathfind clear | "
                        + "/pathfind walk [x y z] | /pathfind stop | /pathfind status. Y is feet height."))
                .then(literal("stop").executes(context -> {
                    paths.clear();
                    return say(context, "Stopped.");
                }))
                .then(literal("walk")
                        .executes(context -> paths.walk(client(context), null))
                        .then(coordinates(context -> paths.walk(client(context), point(context)))))
                .then(literal("clear").executes(context -> {
                    paths.clear();
                    return say(context, "Route cleared.");
                }))
                .then(literal("status").executes(context -> say(context, NativePathfinder.isAvailable()
                        ? "Rust ready. " + paths.status()
                        : "Rust unavailable: " + NativePathfinder.loadError())))
                .then(literal("look").executes(RustPathModClient::lookCommand))
                .then(coordinates(context -> paths.request(client(context), point(context)))));
    }

    /** The x y z arguments, then {@code command}. */
    private static ArgumentBuilder<FabricClientCommandSource, ?> coordinates(Command<FabricClientCommandSource> command) {
        return argument("x", integer(-29_999_984, 29_999_984))
                .then(argument("y", integer(-2048, 2048))
                        .then(argument("z", integer(-29_999_984, 29_999_984))
                                .executes(command)));
    }

    /** /pathfind look: preview a route to the block you're looking at (standing on top of it). */
    private static int lookCommand(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = client(context);
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            PathController.say(client, "Look at a nearby floor block first.");
            return 0;
        }
        var target = hit.getBlockPos().above();
        return paths.request(client, new Point(target.getX(), target.getY(), target.getZ()));
    }

    private static Minecraft client(CommandContext<FabricClientCommandSource> context) {
        return context.getSource().getClient();
    }

    private static Point point(CommandContext<FabricClientCommandSource> context) {
        return new Point(getInteger(context, "x"), getInteger(context, "y"), getInteger(context, "z"));
    }

    private static int say(CommandContext<FabricClientCommandSource> context, String message) {
        PathController.say(client(context), message);
        return 1;
    }
}
