//! The 16 movements, mirroring the loaded-chunk branches of `Moves.java`.
//!
//! There is deliberately no "cached terrain" branch: Java resolves cached
//! chunks into the same per-block grid, so a jump costs a jump everywhere.
//! (The Java planner's column-snapping let a traverse climb 2 blocks for
//! ~1.6x a flat step in cached chunks, which is what made paths hop up and
//! over block piles.)

use crate::costs::{Costs, JUMP_PENALTY};
use crate::grid::Grid;
use std::collections::HashMap;

/// Vanilla auto-step height.
pub const STEP_HEIGHT: f64 = 0.6;

const CARDINALS: [(i32, i32); 4] = [(0, -1), (0, 1), (1, 0), (-1, 0)];
const DIAGONALS: [(i32, i32); 4] = [(1, -1), (-1, -1), (1, 1), (-1, 1)];

#[derive(Clone, Copy, Debug)]
pub struct Move {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub cost: f64,
}

pub struct Mover<'g, 'c> {
    pub grid: &'g Grid<'g>,
    pub costs: &'c Costs,
    /// `getMaxAdjacentDropAllSides > 3`, memoized: it is by far the most
    /// expensive check and every cell is evaluated from several neighbours.
    edge_memo: HashMap<u64, bool, crate::search::FastHash>,
}

impl<'g, 'c> Mover<'g, 'c> {
    pub fn new(grid: &'g Grid<'g>, costs: &'c Costs) -> Self {
        Mover { grid, costs, edge_memo: HashMap::with_capacity_and_hasher(1 << 16, Default::default()) }
    }

    /// Append every valid movement out of (x, y, z) to `out`.
    pub fn expand(&mut self, x: i32, y: i32, z: i32, out: &mut Vec<Move>) {
        out.clear();
        for (dx, dz) in CARDINALS {
            if let Some(m) = self.traverse(x, y, z, x + dx, z + dz) {
                out.push(m);
            }
        }
        for (dx, dz) in CARDINALS {
            if let Some(m) = self.ascend(x, y, z, x + dx, z + dz) {
                out.push(m);
            }
        }
        for (dx, dz) in CARDINALS {
            if let Some(m) = self.descend(x, y, z, x + dx, z + dz) {
                out.push(m);
            }
        }
        for (dx, dz) in DIAGONALS {
            if let Some(m) = self.diagonal(x, y, z, x + dx, z + dz) {
                out.push(m);
            }
        }
    }

    /// Body space at a node: Y and Y+1, or Y+1 and Y+2 when standing on a
    /// partial block at the node's own Y.
    #[inline]
    fn body_clear(&self, x: i32, y: i32, z: i32) -> bool {
        let g = self.grid;
        if g.is_partial_block_ground(x, y, z) {
            g.walkable(x, y + 1, z) && g.walkable(x, y + 2, z)
        } else {
            g.walkable(x, y, z) && g.walkable(x, y + 1, z)
        }
    }

    fn traverse(&mut self, x: i32, y: i32, z: i32, dx: i32, dz: i32) -> Option<Move> {
        let g = self.grid;
        let c = self.costs;
        let dy = y;
        if !g.has_ground_support(dx, dy, dz) || g.is_ice_ground(dx, dy, dz) {
            return None;
        }
        if !self.body_clear(dx, dy, dz) {
            return None;
        }

        let diff = (g.surface_height(dx, dy, dz) - g.surface_height(x, y, z)).abs();
        let mut cost = if diff <= 0.5 {
            c.sprint
        } else if diff <= 1.0 {
            if g.partial(dx, dy - 1, dz) || g.partial(dx, dy, dz) {
                c.sprint * 2.0
            } else {
                c.jump_one_block * 2.0 + JUMP_PENALTY
            }
        } else {
            return None;
        };

        if self.near_edge(dx, dy, dz) {
            return None;
        }
        cost *= self.proximity_factor(dx, dy, dz);
        Some(Move { x: dx, y: dy, z: dz, cost })
    }

    fn ascend(&mut self, x: i32, y: i32, z: i32, dx: i32, dz: i32) -> Option<Move> {
        let g = self.grid;
        let c = self.costs;
        let dy = y + 1;
        if !g.has_ground_support(dx, dy, dz) || g.is_ice_ground(dx, dy, dz) {
            return None;
        }
        if !self.body_clear(dx, dy, dz) {
            return None;
        }
        // Head clearance for the jump at the source.
        if g.is_partial_block_ground(x, y, z) {
            if !g.walkable(x, y + 2, z) || !g.walkable(x, y + 3, z) {
                return None;
            }
        } else if !g.walkable(x, y + 2, z) {
            return None;
        }

        let diff = g.surface_height(dx, dy, dz) - g.surface_height(x, y, z);
        if diff > 1.125 {
            return None;
        }
        // A half-block rise (floor->slab, slab->block) and stair->stair are
        // walked with auto-step; only real rises need a jump. Java priced every
        // ascend as a jump, so the planner zigzagged round slab/block seams.
        let cost = if diff <= STEP_HEIGHT {
            c.sprint * 1.2
        } else if g.stair(dx, dy - 1, dz) || g.stair(dx, dy, dz) {
            c.sprint * 2.0
        } else if g.partial(dx, dy - 1, dz) || g.partial(dx, dy, dz) {
            c.jump_one_block * 3.0
        } else {
            c.jump_one_block * 5.0 + JUMP_PENALTY
        };
        Some(Move { x: dx, y: dy, z: dz, cost })
    }

    fn descend(&mut self, x: i32, y: i32, z: i32, dx: i32, dz: i32) -> Option<Move> {
        let g = self.grid;
        let c = self.costs;
        let src_check_y = if g.is_partial_block_ground(x, y, z) { y + 1 } else { y };
        if !g.walkable(dx, src_check_y, dz) || !g.walkable(dx, src_check_y + 1, dz) {
            return None;
        }

        // One-block step down.
        let dy = y - 1;
        if g.has_ground_support(dx, dy, dz) {
            if g.is_ice_ground(dx, dy, dz) {
                return None;
            }
            // Java skipped this for full-block ground, so a "descend" could
            // land *inside* the block beside a flat traverse.
            if !self.body_clear(dx, dy, dz) {
                return None;
            }
            let diff = g.surface_height(x, y, z) - g.surface_height(dx, dy, dz);
            let cost = if diff <= 0.5 {
                c.sprint
            } else if diff <= 1.125 {
                c.walk_off_one_block + c.fall[1]
            } else {
                return None;
            };
            return Some(Move { x: dx, y: dy, z: dz, cost });
        }

        // Ordinary walking routes do not include damaging drops.
        for fall in 2..=3 {
            let dy = y - fall;
            if !g.has_ground_support(dx, dy, dz) {
                continue;
            }
            if !((dy..y).all(|cy| g.walkable(dx, cy, dz))) {
                // Something solid in the shaft: the first ground we hit is it.
                return None;
            }
            if g.is_ice_ground(dx, dy, dz) {
                continue;
            }
            if g.is_partial_block_ground(dx, dy, dz)
                && (!g.walkable(dx, dy + 1, dz) || !g.walkable(dx, dy + 2, dz))
            {
                continue;
            }
            if self.near_edge(dx, dy, dz) {
                continue;
            }
            let fall_idx = fall.min(3) as usize;
            let mut cost = c.walk_off_one_block + c.fall[fall_idx] + c.center_after_fall;
            if fall > 3 {
                cost += (fall - 3) as f64 * c.sprint * 0.5;
            }
            return Some(Move { x: dx, y: dy, z: dz, cost });
        }
        None
    }

    fn diagonal(&mut self, x: i32, y: i32, z: i32, dx: i32, dz: i32) -> Option<Move> {
        let g = self.grid;
        let c = self.costs;
        let dy = y;
        if g.is_ice_ground(dx, dy, dz) {
            return None;
        }

        if !g.has_ground_support(dx, dy, dz) {
            // Diagonal step down one block.
            if !(g.has_ground_support(dx, dy - 1, dz)
                && g.walkable(dx, dy - 1, dz)
                && g.walkable(dx, dy, dz))
            {
                return None;
            }
            if !g.walkable(x, y, dz)
                || !g.walkable(x, y + 1, dz)
                || !g.walkable(dx, y, z)
                || !g.walkable(dx, y + 1, z)
            {
                return None;
            }
            return Some(Move { x: dx, y: dy - 1, z: dz, cost: c.sprint * std::f64::consts::SQRT_2 + c.fall[1] });
        }

        // Both corners must be open so the hitbox doesn't clip them.
        let check_y = if g.is_partial_block_ground(x, y, z) { y + 1 } else { y };
        if !g.walkable(x, check_y, dz)
            || !g.walkable(x, check_y + 1, dz)
            || !g.walkable(dx, check_y, z)
            || !g.walkable(dx, check_y + 1, z)
        {
            return None;
        }
        if !self.body_clear(dx, dy, dz) {
            return None;
        }

        let base = c.sprint * std::f64::consts::SQRT_2;
        let diff = g.surface_height(dx, dy, dz) - g.surface_height(x, y, z);
        let mut cost = if diff <= 0.5 {
            base
        } else if diff <= 1.0 {
            if g.partial(dx, dy - 1, dz) || g.partial(dx, dy, dz) {
                base * 2.0
            } else {
                base + c.jump_one_block * 2.0 + JUMP_PENALTY
            }
        } else {
            return None;
        };

        if self.near_edge(dx, dy, dz) {
            return None;
        }
        cost *= self.proximity_factor(dx, dy, dz);
        Some(Move { x: dx, y: dy, z: dz, cost })
    }

    // ── Penalties (Moves.java: countAdjacentWalls / hasHeadClipCorner) ────

    /// Wall-proximity and L-corner multipliers. A neighbour the player can
    /// step into (the next slab/stair step) is not a wall: counting it made
    /// every staircase node look wall-hugging, and the planner side-stepped.
    fn proximity_factor(&self, x: i32, y: i32, z: i32) -> f64 {
        let surface = self.grid.surface_height(x, y, z);
        // Order: +x, +z, -x, -z so consecutive entries are perpendicular.
        let dirs = [(1, 0), (0, 1), (-1, 0), (0, -1)];
        let walls = dirs.map(|(ox, oz)| !crate::smooth::footprint_fits(self.grid, x + ox, z + oz, y, surface));
        let count = walls.iter().filter(|w| **w).count();
        let mut factor = 1.0 + count as f64 * 0.5;
        if (0..4).any(|i| walls[i] && walls[(i + 1) % 4]) {
            factor *= 1.5;
        }
        factor
    }

    /// True when a 2+/3+/4+ block drop sits within 1/2/3 blocks.
    pub(crate) fn near_edge(&mut self, x: i32, y: i32, z: i32) -> bool {
        let key = self.grid.key(x, y, z);
        if let Some(&v) = self.edge_memo.get(&key) {
            return v;
        }
        let v = self.compute_near_edge(x, y, z);
        self.edge_memo.insert(key, v);
        v
    }

    fn compute_near_edge(&self, x: i32, y: i32, z: i32) -> bool {
        let g = self.grid;
        for (ox, oz) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
            for dist in 1..=3 {
                let cx = x + ox * dist;
                let cz = z + oz * dist;
                let start = y + 2;
                let ground = (start - 15..=start).rev().find(|&cy| g.has_ground_support(cx, cy, cz));
                match ground {
                    None if dist <= 2 => return true,
                    None => {}
                    Some(gy) => {
                        if y - gy > dist {
                            return true;
                        }
                    }
                }
            }
        }
        false
    }
}
