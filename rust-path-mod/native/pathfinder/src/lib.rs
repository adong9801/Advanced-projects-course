//! The Rust side of the pathfinder mod. Java loads this library and calls
//! the functions in `jni.rs`.
//!
//! `v5` is the search the mod uses. `search`, `moves`, `grid`, `costs` and
//! `smooth` are the first version of the mod's search, from before it was
//! changed to match the Java mod's. The mod doesn't use them any more, but they're
//! still built and their tests still run.

pub mod costs;
pub mod grid;
mod jni;
pub mod moves;
pub mod search;
pub mod smooth;

pub mod v5;
