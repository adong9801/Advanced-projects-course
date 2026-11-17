//! The search the mod uses now: a Rust version of the walking search in my
//! pure Java pathfinder mod, which is based on V5's pathfinder
//! (https://github.com/V5-Client/V5).
//!
//! - `world.rs`: the block snapshot from Java and what each block is
//! - `moves.rs`: which moves are possible from a block and what they cost
//! - `search.rs`: the A* search that puts the moves together into a route
//!
//! The comparison tests (V5ParityTest.java) run the same searches through
//! this and the Java version and check the routes are exactly the same. So
//! some things are written the way Java does them on purpose: costs are f32,
//! the moves are tried in the same order, and the open set is the same kind
//! of heap. Changing any of those can change which route wins a tie.

mod moves;
mod search;
mod world;

pub use search::{find, Avoid, Query, SearchResult, Status};
pub use world::{Grid, Pos};

#[cfg(test)]
mod tests;
