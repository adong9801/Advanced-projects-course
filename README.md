# Rust Pathfinder

A Minecraft pathfinder for my course project: Rust finds the route, and Java walks the player along it.

- Final video: https://www.youtube.com/watch?v=QDvjQnDj4-g
- Documentation: [Rust Pathfinder Project Documentation](https://docs.google.com/document/d/1zqXj8fdr9FvEjBLE7OsOSS8yZYWv2avIebudq20dNKg/edit)
- Earlier demo (November 2): https://youtu.be/0yST2iTAc5M

## Who wrote what

Most of the Rust in this project is mine. The Rust port of my pure Java pathfinder mod's
search was split between me and AI. The JNI bridge (`jni.rs`, where Java calls into Rust)
was written by AI, and I also used AI to clean up the code for the final video.

The Java movement code (`V5PathWalker.java`) comes from my pure Java pathfinder mod. Both
the search and the walker are based on [V5 by Rdbt and contributors](https://github.com/V5-Client/V5).

## Jump to the code

### First version: the terminal prototype (`src/`)

| What | Where |
|---|---|
| A position, and the "can I stand here?" checks | [main.rs lines 3–32](src/main.rs#L3-L32) |
| The moves from a position: walk, jump up, drop | [main.rs lines 34–92](src/main.rs#L34-L92) |
| Move costs and the heuristic | [main.rs lines 94–103](src/main.rs#L94-L103) |
| The A* search | [main.rs lines 133–181](src/main.rs#L133-L181) |
| The wall example and the terminal grid | [main.rs lines 183–275](src/main.rs#L183-L275) |
| Tests | [tests.rs](src/tests.rs) |

### The Minecraft mod (`rust-path-mod/`)

Rust, the search:

| What | Where |
|---|---|
| The main A* loop | [search.rs lines 322–397](rust-path-mod/native/pathfinder/src/v5/search.rs#L322-L397) |
| Keeping the cheapest way to each block, and rebuilding the route | [search.rs lines 293–319](rust-path-mod/native/pathfinder/src/v5/search.rs#L293-L319) |
| Walking, jumping up and dropping down | [moves.rs lines 248–329](rust-path-mod/native/pathfinder/src/v5/moves.rs#L248-L329) |
| What each move costs | [moves.rs lines 11–22](rust-path-mod/native/pathfinder/src/v5/moves.rs#L11-L22) |
| The block snapshot Rust searches | [world.rs lines 45–103](rust-path-mod/native/pathfinder/src/v5/world.rs#L45-L103) |
| The wall example from my documentation, as tests | [v5/tests.rs](rust-path-mod/native/pathfinder/src/v5/tests.rs) |
| Where Java calls into Rust (JNI) | [jni.rs lines 306–354](rust-path-mod/native/pathfinder/src/jni.rs#L306-L354) |

Java, the Minecraft side:

| What | Where |
|---|---|
| Copying the blocks into a snapshot | [V5World.java lines 184–209](rust-path-mod/src/client/java/com/rustpath/client/pathfinder/V5World.java#L184-L209) |
| Sending a search to Rust and reading the route back | [V5Search.java lines 47–75](rust-path-mod/src/client/java/com/rustpath/client/pathfinder/V5Search.java#L47-L75) |
| The /pathfind commands | [PathController.java](rust-path-mod/src/client/java/com/rustpath/client/pathfinder/PathController.java) |
| Walking the route (from my Java pathfinder mod, based on V5) | [V5PathWalker.java](rust-path-mod/src/client/java/com/rustpath/client/pathfinder/V5PathWalker.java) |

Tests:

| What | Where |
|---|---|
| 307 cases comparing the Rust and Java searches | [V5ParityTest.java](rust-path-mod/src/nativeTest/java/com/rustpath/client/pathfinder/V5ParityTest.java) |
| Movement tests in a real Minecraft world | [WalkingGameTest.java](rust-path-mod/src/gametest/java/com/rustpath/test/WalkingGameTest.java) |

## How it fits together

1. Java copies the blocks around the route out of Minecraft into a snapshot.
2. Rust searches the snapshot with A* and sends the route back through JNI.
3. Java draws the route and walks the player along it.

## Run it

- First version: `cargo run` prints the route and the grid; `cargo test` runs its tests.
- The mod (needs JDK 25 and Rust): `cd rust-path-mod`, then `./gradlew build` builds it
  and runs all the tests, and `./gradlew runClient` opens Minecraft with the mod.
  In game, `/pathfind walk x y z` walks there.

## License

The mod in `rust-path-mod/` is based on [V5](https://github.com/V5-Client/V5), which is
GPL-3.0, so it's licensed under GPL-3.0 too (see `rust-path-mod/LICENSE`).
