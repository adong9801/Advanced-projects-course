# Rust Minecraft Pathfinder

A standalone Fabric client mod for **Minecraft 26.1.2**. Rust calculates paths;
the movement controller from my pure Java pathfinder mod walks them. Install Fabric API alongside it.
This version implements the agreed scope: **walking, jumping, drops, and route display**.

## Commands

- `/pathfind <x> <y> <z>` previews a route without moving.
- `/pathfind look` previews a route to the floor under your crosshair.
- `/pathfind walk` walks to the last requested destination, searching from your current position.
- `/pathfind walk <x> <y> <z>` searches and walks directly.
- `/pathfind stop` or **F7** stops walking and releases the movement keys.
- `/pathfind clear` clears the route and cancels the request.
- `/pathfind status` reports loading, searching, walking, or preview state.

Coordinates are absolute feet coordinates. For a full floor block at Y=64, use
Y=65. Slab, stair, and snow destinations are resolved the way my Java pathfinder mod resolves them.
The cyan line shows the Java walker's smoothed route; green marks its start and
yellow its destination. A failed search does not start walking a partial route.

## What is Rust and what is Java?

**Rust:** A* search, movement choices, route costs, cached wall/edge penalties,
multiple starts/goals, avoid zones, route reconstruction, node annotations, and
the indexed four-way priority queue. No Java search fallback runs in the mod.

**Java:** Minecraft block classification and collision geometry, the immutable
world snapshot, JNI, commands/rendering, and the Java mod's movement controller.
The controller retains its steering, sprint/corner handling, jump prediction,
slab/stair/snow handling, drop braking, and stuck recovery. Recalculations also
use Rust. Minecraft reads are batched on the client thread; the background
search receives only immutable buffers.

The reference is my Java pathfinder mod's V5 code, captured on **November 15, 2026**.
Source hashes are recorded in `tools/parity/java-reference.json`.
Mining, recorded-movement playback, and teleporting are not enabled.
The copied controller retains those upstream extension hooks, with no commands
or configuration enabling them in this standalone mod.

## What “matching the Java search” means

The native comparison suite checks the **exact path, explored-node count,
chosen start, node flags, and path signature** against the Java search.
It currently includes **307 cases**: flat terrain, walls, steps, slabs, stairs,
low ceilings, fluids, fences, corner-clearance flags, drops from 1 to 21 blocks,
multiple starts/goals, avoid zones, jump-height settings, node limits, and
60 randomized worlds with four move-order seeds each.

The Java reference lives only in the test source set. Its search body is
unchanged; Minecraft/world and map classes have small standalone substitutes.
The test reference is **not included in the mod jar**.

A separate client game test creates its own temporary world and exercises real
Minecraft physics, route preview, and stopping. It does not use your saves or a
multiplayer server. Screenshots and logs are saved under
`build/run/clientGameTest/`.

These checks establish parity for those inputs and the selected feature scope;
they do not prove every possible world or server condition. Rust alone does not
guarantee a speedup. No Java-versus-Rust performance improvement is claimed here.
The preview message reports search time separately from total snapshot/search time.

## Limits and next work

- Searches use loaded terrain in a bounded snapshot: target within 128 blocks on
  X/Z and 48 on Y, with 32 blocks of horizontal and 24 of vertical detour space.
  The engine has a 500,000-iteration / 5-second search budget. The Java mod's live-world
  reader does not have this same snapshot boundary.
- Ordinary walking uses the Java mod's default one-block climb limit. The walker uses
  the actual player's speed/jump physics, but the shaft-specific boosted-climb
  selection and recorded routes are outside this version's scope.
- Drops follow the Java mod's preference for avoiding drops when a nearby route is
  available, but it can still choose drops up to 20 blocks. That is not a promise
  that a drop is harmless in survival.
- Routes reflect a snapshot. The walker checks for changed obstacles and can
  replan; this is not a full simulation of moving entities or server latency.
- Native libraries are specific to the build machine's OS/architecture. This
  Mac ARM build needs a corresponding native rebuild for Windows/Linux/Intel.

Useful next work: larger/incremental snapshots, release-mode performance
comparisons on identical captured worlds, and a wider set of live movement tests.

## Build and test

Install JDK 25, Rust, and Cargo, then run:

```sh
./gradlew build
./gradlew runClient
```

`build` compiles the release Rust library, bundles it, runs the Rust tests,
checks the JNI boundary, and runs the 307 Java/Rust comparisons.
The installable jar is `build/libs/rust-path-mod-1.0.0.jar`.
The `-sources.jar` contains source, not an installable mod.

Run the optional real-client tests with:

```sh
./gradlew runClientGameTest
```

This opens a test Minecraft instance and closes it after the tests.
The test setup follows [Fabric's client game test documentation](https://docs.fabricmc.net/develop/automatic-testing).
Cargo is needed to build, not to play. A missing native library produces a clear
error instead of silently falling back to Java.

## Source map

- `native/pathfinder/src/v5/`: the current Rust search. `world.rs` is the block
  snapshot, `moves.rs` the moves and their costs, `search.rs` the A* search.
- `native/pathfinder/src/jni.rs`: the functions Java calls, and the checks on what it sends.
- `src/client/java/com/rustpath/client/pathfinder/V5Search.java`: Java search adapter.
- `V5Flags.java` / `V5World.java`: Block flags and batched world capture.
- `V5PathWalker.java` / `V5PathRotation.java` / `Approach.java`: copied Java movement.
- `PathController.java` / `PathRenderer.java`: standalone lifecycle and drawing.
- `src/nativeTest/`: native integration and exact Java/Rust comparisons.
- `src/gametest/`: real-client movement checks.

The older Rust `search.rs`, `moves.rs`, `grid.rs`, and `smooth.rs` remain as the
previous implementation for study and regression tests. The commands use `v5`.
The search and walker are based on [V5](https://github.com/V5-Client/V5) by Rdbt and contributors.

## License

V5 is GPL-3.0, and this mod is based on it, so this mod is GPL-3.0 too (see `LICENSE`).
