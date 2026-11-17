package com.rustpath.test;

import com.rustpath.client.RustPathModClient;
import com.rustpath.client.pathfinder.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;

/** Real Minecraft physics, a new disposable world, no player saves or servers. */
public final class WalkingGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try(var world=context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            var server=world.getServer();
            server.runCommand("fill -12 100 -12 40 100 12 stone");
            server.runCommand("fill -12 101 -12 40 110 12 air");
            server.runCommand("gamemode survival @a");
            server.runCommand("difficulty peaceful");
            server.runCommand("tp @a 0.5 101 0.5 -90 0");
            context.waitTicks(30);
            walk(context,20,101,0,"flat");
            server.runCommand("fill 10 101 -5 10 103 5 stone");
            server.runCommand("tp @a 0.5 101 0.5 -90 0");context.waitTicks(25);
            walk(context,20,101,0,"wall detour");
            server.runCommand("fill 10 101 -5 10 103 5 air");
            server.runCommand("fill 10 101 -12 40 101 12 stone");
            server.runCommand("tp @a 0.5 101 0.5 -90 0");context.waitTicks(25);
            walk(context,20,102,0,"one-block jump");
            walk(context,0,101,0,"drop back down");
            context.runOnClient(mc->RustPathModClient.controller().request(mc,new NativePathfinder.Point(20,102,0)));
            context.waitFor(mc->!RustPathModClient.controller().busy(),600);
            context.runOnClient(mc->{if(RustPathModClient.controller().path().isEmpty())throw new AssertionError("Preview did not draw route");});
            context.getInput().lookAt(-90,20);
            context.waitTicks(3);
            context.takeScreenshot("rust-pathfinder-preview");
            server.runCommand("fill 10 101 -12 40 101 12 stone_slab[type=bottom]");
            server.runCommand("tp @a 0.5 101 0.5 -90 0");context.waitTicks(25);
            walk(context,20,102,0,"bottom slabs");
            server.runCommand("fill 10 101 -12 40 101 12 stone_stairs[facing=east]");
            server.runCommand("tp @a 0.5 101 0.5 -90 0");context.waitTicks(25);
            walk(context,20,102,0,"stairs");
            server.runCommand("fill 10 101 -12 40 101 12 snow[layers=6]");
            server.runCommand("tp @a 0.5 101 0.5 -90 0");context.waitTicks(25);
            walk(context,20,102,0,"snow layers");
            server.runCommand("fill 10 98 -12 40 104 12 air");
            server.runCommand("fill 10 97 -12 40 97 12 stone");
            server.runCommand("tp @a 0.5 101 0.5 -90 0");context.waitTicks(25);
            walk(context,20,98,0,"three-block drop");
            context.runOnClient(mc->{RustPathModClient.controller().walk(mc,new NativePathfinder.Point(15,98,0));RustPathModClient.controller().clear();});
            context.waitTicks(10);
            context.runOnClient(mc->{if(V5PathWalker.isActive()||mc.options.keyUp.isDown()||mc.options.keyJump.isDown())throw new AssertionError("Stop left movement active");});
        }
    }
    private void walk(ClientGameTestContext context,int x,int y,int z,String label){
        context.runOnClient(mc->RustPathModClient.controller().walk(mc,new NativePathfinder.Point(x,y,z)));
        context.waitFor(mc->!RustPathModClient.controller().isWalking(),900);
        context.runOnClient(mc->{
            double distance=Math.hypot(mc.player.getX()-(x+0.5),mc.player.getZ()-(z+0.5));
            if(!V5PathWalker.succeeded()||distance>1.2||Math.abs(mc.player.getY()-y)>0.7)
                throw new AssertionError(label+": failed at "+mc.player.position()+" reason "+V5PathWalker.lastFailure());
            System.out.println("Movement check passed: "+label+" at "+mc.player.position());
        });
    }
}
