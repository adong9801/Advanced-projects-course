//! The simple wall example from the project documentation, run through the current search:
//! a start and a goal 5 blocks apart with a wall between them.

use super::*;
use std::time::Duration;

const STONE: u16 = world::SOLID | world::WALL;

/// A stone floor (top at y = 63) from x -1 to 6 and z -4 to 4, with a wall
/// `height` blocks tall at x = 2, from z -2 to 2.
fn wall_example(height: i32) -> (Vec<u16>, Vec<u8>, Pos, Pos) {
    let min = [-1, 62, -4];
    let size = [8, 5, 9];
    let mut cells = vec![world::AIR; (size[0] * size[1] * size[2]) as usize];
    let index = |x: i32, y: i32, z: i32| {
        (((x - min[0]) * size[1] + (y - min[1])) * size[2] + (z - min[2])) as usize
    };
    for x in -1..=6 {
        for z in -4..=4 {
            cells[index(x, 62, z)] = STONE;
            cells[index(x, 63, z)] = STONE;
        }
    }
    for z in -2..=2 {
        for y in 64..64 + height {
            cells[index(2, y, z)] = STONE;
        }
    }
    let corners = vec![0; cells.len()];
    (cells, corners, min, size)
}

fn route(height: i32) -> Vec<Pos> {
    let (cells, corners, min, size) = wall_example(height);
    let grid = Grid { cells: &cells, corners: &corners, min, size, world_y: [-64, 320] };
    let query = Query {
        starts: &[[0, 64, 0]],
        goals: &[[5, 64, 0]],
        max_iterations: 100_000,
        seed: 0,
        max_climb: 1,
        avoid: &[],
        timeout: Duration::from_secs(5),
    };
    let result = find(&grid, &query);
    assert_eq!(result.status, Status::Found);
    result.path
}

#[test]
fn jumps_over_a_one_block_wall() {
    // The same route the first version found: walk, jump onto the wall, drop off, walk
    assert_eq!(
        route(1),
        vec![[0, 64, 0], [1, 64, 0], [2, 65, 0], [3, 64, 0], [4, 64, 0], [5, 64, 0]]
    );
}

#[test]
fn walks_round_a_two_block_wall() {
    let path = route(2);
    assert_eq!(path.last(), Some(&[5, 64, 0]));
    assert!(path.iter().all(|p| p[1] == 64), "climbed the wall: {path:?}");
    assert!(path.iter().any(|p| p[2].abs() > 2), "didn't go round the end: {path:?}");
}
